"""Desktop window and authenticated local-network receiver."""

from __future__ import annotations

import queue
import secrets
import shutil
import socket
import socketserver
import subprocess
import os
import threading
import time
import tkinter as tk
from collections import deque
from datetime import datetime, timezone
from pathlib import Path
from tkinter import filedialog, messagebox, ttk
from typing import Callable

from .protocol import PACKET_SIZE, ControllerState, Frame, decode
from .virtual_controller import VirtualController, create_virtual_controller

PORT = 26760
USB_PORT = 26762
DISCOVERY_PORT = 26761
DISCOVERY_REQUEST = b"POCKETPAD_DISCOVER_V1"
WATCHDOG_SECONDS = 0.2


def local_address() -> str:
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("192.0.2.1", 80))
        return str(probe.getsockname()[0])
    finally:
        probe.close()


def companion_name() -> str:
    return socket.gethostname().strip() or "PocketPad Host"


def encode_discovery_response(name: str, port: int = PORT) -> bytes:
    if not name.strip() or "|" in name or not 1 <= port <= 65535:
        raise ValueError("Invalid PocketPad discovery response fields.")
    return f"POCKETPAD_HOST_V1|{name.strip()}|{port}".encode("utf-8")


def usb_device_serials(adb_output: str) -> list[str]:
    devices = []
    for line in adb_output.splitlines()[1:]:
        fields = line.split()
        if (
            len(fields) >= 2
            and fields[1] == "device"
            and ":" not in fields[0]
            and not fields[0].startswith("emulator-")
        ):
            devices.append(fields[0])
    return devices


def has_usb_reverse_mapping(output: str, serial: str, port: int) -> bool:
    mapping = f"tcp:{port}"
    for line in output.splitlines():
        fields = line.split()
        if len(fields) == 3 and fields[1:] == [mapping, mapping]:
            # `adb -s SERIAL reverse --list` omits the serial on some platform-tools versions.
            return True
        if len(fields) == 4 and fields[0] == serial and fields[2:] == [mapping, mapping]:
            return True
    return False


def find_adb() -> str | None:
    executable = "adb.exe" if os.name == "nt" else "adb"
    candidates = [
        shutil.which("adb"),
        str(Path(os.environ.get("ANDROID_HOME", "")) / "platform-tools" / executable),
        str(Path(os.environ.get("ANDROID_SDK_ROOT", "")) / "platform-tools" / executable),
        str(Path.home() / "AppData" / "Local" / "Android" / "Sdk" / "platform-tools" / executable),
        str(Path.home() / "Android" / "Sdk" / "platform-tools" / executable),
        str(Path.home() / "Library" / "Android" / "sdk" / "platform-tools" / executable),
    ]
    return next((candidate for candidate in candidates if candidate and Path(candidate).is_file()), None)


class Receiver:
    def __init__(
        self,
        key: bytes,
        address: str,
        messages: queue.Queue[str],
        udp_port: int = PORT,
        discovery_port: int = DISCOVERY_PORT,
        usb_port: int = USB_PORT,
        virtual_controller_factory: Callable[[], VirtualController] | None = None,
    ) -> None:
        self.key = key
        self.address = address
        self.messages = messages
        self.stop_event = threading.Event()
        self.last_packet = 0.0
        self.last_sequences: dict[tuple[str, int], int] = {}
        self.current_state = ControllerState.neutral()
        self.logs: deque[str] = deque(maxlen=5000)
        self._log_lock = threading.Lock()
        self._state_lock = threading.Lock()
        self.usb_port = usb_port
        try:
            self.virtual_pad = (
                virtual_controller_factory() if virtual_controller_factory else create_virtual_controller()
            )
            self.backend_name = self.virtual_pad.name
        except RuntimeError as error:
            self.virtual_pad = None
            self.backend_name = f"Input monitor only: {error}"
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.socket.bind(("0.0.0.0", udp_port))
        self.socket.settimeout(0.05)
        self.thread = threading.Thread(target=self._run, name="PocketPad-UDP", daemon=True)
        self.discovery_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.discovery_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.discovery_socket.bind(("0.0.0.0", discovery_port))
        self.discovery_socket.settimeout(0.25)
        self.discovery_thread = threading.Thread(
            target=self._discover, name="PocketPad-Discovery", daemon=True
        )
        receiver = self

        class TcpServer(socketserver.ThreadingTCPServer):
            allow_reuse_address = True
            daemon_threads = True
            request_queue_size = 1

        class TcpHandler(socketserver.BaseRequestHandler):
            def handle(self) -> None:
                self.request.settimeout(0.25)
                last_sequence: int | None = None
                pending = bytearray()
                while not receiver.stop_event.is_set():
                    try:
                        packet = receiver._receive_tcp_packet(self.request, pending)
                    except socket.timeout:
                        continue
                    except (ConnectionError, OSError):
                        return
                    if packet is None:
                        return
                    try:
                        frame = decode(packet, receiver.key)
                    except ValueError as error:
                        receiver.log("WARN", f"Rejected invalid USB controller packet: {error}")
                        return
                    if last_sequence is not None:
                        delta = (frame.sequence - last_sequence) & 0xFFFFFFFF
                        if delta == 0 or delta >= 0x80000000:
                            receiver.log("WARN", f"Rejected stale USB sequence {frame.sequence}.")
                            return
                    last_sequence = frame.sequence
                    receiver._apply_input(frame, "USB")
                    try:
                        self.request.sendall(packet)
                    except OSError:
                        return

        self.tcp_server = TcpServer(("127.0.0.1", usb_port), TcpHandler)
        self.tcp_thread = threading.Thread(
            target=self.tcp_server.serve_forever,
            kwargs={"poll_interval": 0.2},
            name="PocketPad-USB-TCP",
            daemon=True,
        )
        self.adb_serial: str | None = None
        self.adb_monitor_thread: threading.Thread | None = None

    def start(self) -> None:
        self.thread.start()
        self.discovery_thread.start()
        self.tcp_thread.start()

    def close(self) -> None:
        self.stop_event.set()
        self.tcp_server.shutdown()
        self.tcp_server.server_close()
        self.tcp_thread.join(timeout=1.0)
        if self.adb_monitor_thread:
            self.adb_monitor_thread.join(timeout=2.0)
        if self.adb_serial:
            adb = find_adb()
            if adb:
                try:
                    result = subprocess.run(
                        [adb, "-s", self.adb_serial, "reverse", "--remove", f"tcp:{self.usb_port}"],
                        capture_output=True,
                        text=True,
                        timeout=10,
                        check=False,
                    )
                    if result.returncode != 0:
                        self.log("WARN", result.stderr.strip() or "Could not remove the USB reverse mapping.")
                except (OSError, subprocess.TimeoutExpired) as error:
                    self.log("WARN", f"Could not remove the USB reverse mapping: {error}")
        self.thread.join(timeout=1.0)
        self.discovery_socket.close()
        self.discovery_thread.join(timeout=1.0)
        self.socket.close()
        if self.virtual_pad:
            self.virtual_pad.close()

    @staticmethod
    def _receive_tcp_packet(
        connection: socket.socket,
        pending: bytearray | None = None,
    ) -> bytes | None:
        buffer = pending if pending is not None else bytearray()
        while len(buffer) < PACKET_SIZE:
            chunk = connection.recv(PACKET_SIZE - len(buffer))
            if not chunk:
                return None
            buffer.extend(chunk)
        frame = bytes(buffer[:PACKET_SIZE])
        del buffer[:PACKET_SIZE]
        return frame

    def setup_usb_tunnel(self) -> str:
        adb = find_adb()
        if adb is None:
            raise RuntimeError("Android platform-tools (adb) were not found. Install them and enable USB debugging.")
        result = subprocess.run(
            [adb, "devices"],
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip() or "Could not query adb devices.")
        devices = usb_device_serials(result.stdout)
        if len(devices) != 1:
            raise RuntimeError(
                "Connect and authorize exactly one Android phone over USB. "
                f"Found {len(devices)} authorized USB devices."
            )
        serial = devices[0]
        result = subprocess.run(
            [adb, "-s", serial, "reverse", f"tcp:{self.usb_port}", f"tcp:{self.usb_port}"],
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip() or "adb could not establish the USB reverse tunnel.")
        self.adb_serial = serial
        self.log("INFO", f"USB ADB reverse tunnel enabled for device {serial}.")
        if self.adb_monitor_thread is None or not self.adb_monitor_thread.is_alive():
            self.adb_monitor_thread = threading.Thread(
                target=self._monitor_usb_tunnel,
                args=(adb, serial),
                name="PocketPad-ADB-Monitor",
                daemon=True,
            )
            self.adb_monitor_thread.start()
        return serial

    def _monitor_usb_tunnel(self, adb: str, serial: str) -> None:
        last_error = ""
        while not self.stop_event.wait(1.0):
            if self.adb_serial != serial:
                return
            try:
                state = subprocess.run(
                    [adb, "-s", serial, "get-state"],
                    capture_output=True,
                    text=True,
                    timeout=3,
                    check=False,
                )
                if state.returncode != 0 or state.stdout.strip() != "device":
                    continue
                mappings = subprocess.run(
                    [adb, "-s", serial, "reverse", "--list"],
                    capture_output=True,
                    text=True,
                    timeout=3,
                    check=False,
                )
                expected_mapping = f"tcp:{self.usb_port}"
                mapping_exists = mappings.returncode == 0 and has_usb_reverse_mapping(
                    mappings.stdout, serial, self.usb_port
                )
                if mapping_exists:
                    last_error = ""
                    continue
                result = subprocess.run(
                    [adb, "-s", serial, "reverse", expected_mapping, expected_mapping],
                    capture_output=True,
                    text=True,
                    timeout=5,
                    check=False,
                )
                if result.returncode == 0:
                    self.log("INFO", f"USB cable is available; restored ADB reverse tunnel for {serial}.")
                    last_error = ""
                else:
                    detail = result.stderr.strip() or "adb could not restore the reverse tunnel."
                    if detail != last_error:
                        self.log("WARN", detail)
                        last_error = detail
            except (OSError, subprocess.TimeoutExpired) as error:
                detail = str(error)
                if detail != last_error:
                    self.log("WARN", f"Could not check the USB reverse tunnel: {detail}")
                    last_error = detail

    def _run(self) -> None:
        while not self.stop_event.is_set():
            try:
                data, peer = self.socket.recvfrom(1024)
            except socket.timeout:
                self._watchdog()
                continue
            except OSError:
                if not self.stop_event.is_set():
                    self.messages.put("Network socket closed unexpectedly.")
                    self.log("ERROR", "Network socket closed unexpectedly.")
                    return

            try:
                frame = decode(data, self.key)
            except ValueError:
                self.log("WARN", f"Rejected unauthenticated/invalid packet from {peer[0]}.")
                continue
            previous_sequence = self.last_sequences.get(peer)
            if previous_sequence is not None:
                delta = (frame.sequence - previous_sequence) & 0xFFFFFFFF
                if delta == 0 or delta >= 0x80000000:
                    self.log("WARN", f"Rejected stale sequence {frame.sequence} from {peer[0]}.")
                    continue
            self.last_sequences[peer] = frame.sequence
            if len(self.last_sequences) > 64:
                del self.last_sequences[next(iter(self.last_sequences))]
            self._apply_input(frame, peer[0])
            try:
                self.socket.sendto(data, peer)
            except OSError as error:
                self.messages.put(f"Could not send latency reply: {error}")
                self.log("ERROR", f"Could not send latency reply to {peer[0]}: {error}")

    def _apply_input(self, frame: Frame, peer: str) -> None:
        with self._state_lock:
            self.last_packet = time.monotonic()
            self.current_state = frame.state
            if self.virtual_pad:
                self.virtual_pad.apply(frame.state)
        self.messages.put(
            f"{peer} · seq {frame.sequence} · "
            f"buttons 0x{frame.state.buttons:08X} · {self.backend_name}"
        )
        self.log(
            "INPUT",
            f"peer={peer} sequence={frame.sequence} buttons=0x{frame.state.buttons:08X} "
            f"left=({frame.state.left_x},{frame.state.left_y}) "
            f"right=({frame.state.right_x},{frame.state.right_y})",
        )

    def _discover(self) -> None:
        response = encode_discovery_response(companion_name())
        while not self.stop_event.is_set():
            try:
                request, peer = self.discovery_socket.recvfrom(256)
            except socket.timeout:
                continue
            except OSError:
                return
            if request.strip() != DISCOVERY_REQUEST:
                continue
            try:
                self.discovery_socket.sendto(response, peer)
            except OSError as error:
                self.log("ERROR", f"Discovery response to {peer[0]} failed: {error}")

    def log(self, level: str, message: str) -> None:
        timestamp = datetime.now(timezone.utc).astimezone().isoformat(timespec="milliseconds")
        with self._log_lock:
            self.logs.append(f"{timestamp} {level} {message}")

    def export_logs(self, path: str) -> None:
        with self._log_lock:
            lines = list(self.logs)
        with open(path, "w", encoding="utf-8", newline="\n") as output:
            output.write("\n".join(lines))
            if lines:
                output.write("\n")

    def _watchdog(self) -> None:
        with self._state_lock:
            if not self.last_packet or time.monotonic() - self.last_packet <= WATCHDOG_SECONDS:
                return
            self.current_state = ControllerState.neutral()
            self.last_packet = 0.0
            if self.virtual_pad:
                self.virtual_pad.apply(self.current_state)
            self.messages.put("Input watchdog released all controls.")


class PocketPadWindow:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.root.title("PocketPad PC Companion")
        self.root.geometry("560x560")
        self.root.minsize(520, 520)
        self.messages: queue.Queue[str] = queue.Queue()
        self.key = secrets.token_bytes(32)
        self.ip = local_address()
        self.name = companion_name()
        self.share_text = (
            f"pocketpad://connect?host={self.ip}&port={PORT}&key={self.key.hex()}"
        )
        self.receiver: Receiver | None = None

        style = ttk.Style()
        style.configure("Title.TLabel", font=("Segoe UI", 18, "bold"))
        outer = ttk.Frame(root, padding=20)
        outer.pack(fill="both", expand=True)
        ttk.Label(outer, text="PocketPad", style="Title.TLabel").pack(anchor="w")
        ttk.Label(outer, text="Pair your phone and send controller input over your local network.").pack(anchor="w", pady=(4, 14))
        ttk.Label(outer, text=f"Host: {self.name} · {self.ip}:{PORT}").pack(anchor="w")
        self.qr_label = ttk.Label(outer, text="QR code unavailable until the optional qrcode package is installed.")
        self.qr_label.pack(anchor="w", pady=8)
        self.qr_image = None
        try:
            import qrcode
            from PIL import ImageTk

            image = qrcode.make(self.share_text).resize((180, 180))
            self.qr_image = ImageTk.PhotoImage(image)
            self.qr_label.configure(image=self.qr_image, text="")
        except ImportError:
            pass
        ttk.Label(outer, text="Pairing link (copy this if you cannot scan the code):").pack(anchor="w")
        self.link = tk.Entry(outer)
        self.link.insert(0, self.share_text)
        self.link.configure(state="readonly")
        self.link.pack(fill="x", pady=(4, 10))
        ttk.Button(outer, text="Copy pairing link", command=self.copy_link).pack(anchor="w")
        ttk.Button(outer, text="Set up USB cable (ADB)", command=self.setup_usb_tunnel).pack(anchor="w", pady=(6, 0))
        ttk.Button(outer, text="Export connection logs", command=self.export_logs).pack(anchor="w", pady=(6, 0))
        self.status = ttk.Label(outer, text="Starting listener…")
        self.status.pack(anchor="w", pady=(14, 4))
        self.live = ttk.Label(outer, text="Waiting for a paired phone.")
        self.live.pack(anchor="w")
        ttk.Label(
            outer,
            text="The pairing key grants control of this PC. Share it only with your own phone.",
            wraplength=500
        ).pack(anchor="w", pady=(18, 0))

        try:
            self.receiver = Receiver(self.key, self.ip, self.messages)
            self.receiver.start()
            self.status.configure(
                text=f"Listening Wi-Fi UDP {PORT} · USB TCP {USB_PORT} · discovery UDP {DISCOVERY_PORT} · {self.receiver.backend_name}"
            )
        except OSError as error:
            self.status.configure(text=f"Could not start Companion listeners: {error}")
            messagebox.showerror("PocketPad Companion", str(error), parent=self.root)
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.root.after(100, self.poll_messages)

    def copy_link(self) -> None:
        self.root.clipboard_clear()
        self.root.clipboard_append(self.share_text)
        self.status.configure(text="Pairing link copied to clipboard.")

    def setup_usb_tunnel(self) -> None:
        if self.receiver is None:
            messagebox.showerror("PocketPad Companion", "The Companion listener is not running.", parent=self.root)
            return
        try:
            serial = self.receiver.setup_usb_tunnel()
        except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
            messagebox.showerror("USB setup failed", str(error), parent=self.root)
            return
        self.status.configure(text=f"USB tunnel ready for {serial}. Paste the pairing link in PocketPad and choose USB.")
        messagebox.showinfo(
            "USB tunnel ready",
            "ADB reverse is active. Keep USB debugging enabled, paste the Companion pairing link in PocketPad, then choose USB Cable and Connect.",
            parent=self.root,
        )

    def export_logs(self) -> None:
        path = filedialog.asksaveasfilename(
            parent=self.root,
            title="Export PocketPad connection logs",
            defaultextension=".log",
            filetypes=(("Log files", "*.log"), ("Text files", "*.txt")),
            initialfile="pocketpad-connection.log",
        )
        if not path:
            return
        if self.receiver is None:
            messagebox.showerror("PocketPad Companion", "The Companion listener is not running.", parent=self.root)
            return
        try:
            self.receiver.export_logs(path)
        except OSError as error:
            messagebox.showerror("Could not export logs", str(error), parent=self.root)
            return
        self.status.configure(text=f"Connection log exported to {path}")

    def poll_messages(self) -> None:
        try:
            while True:
                self.live.configure(text=self.messages.get_nowait())
        except queue.Empty:
            pass
        if self.receiver:
            state = self.receiver.current_state
            self.live.configure(
                text=f"Buttons 0x{state.buttons:08X} · "
                f"left ({state.left_x}, {state.left_y}) · "
                f"right ({state.right_x}, {state.right_y}) · "
                f"triggers ({state.left_trigger}, {state.right_trigger})"
            )
        self.root.after(100, self.poll_messages)

    def close(self) -> None:
        if self.receiver:
            self.receiver.close()
            self.receiver = None
        self.root.destroy()


def main() -> None:
    root = tk.Tk()
    PocketPadWindow(root)
    root.mainloop()


if __name__ == "__main__":
    main()
