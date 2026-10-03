import unittest
import queue
import socket
import time

from pocketpad_companion.app import (
    DISCOVERY_REQUEST,
    Receiver,
    encode_discovery_response,
    has_usb_reverse_mapping,
    usb_device_serials,
)
from pocketpad_companion.protocol import PACKET_SIZE, ControllerState, decode, encode


class RecordingController:
    name = "test controller"

    def __init__(self):
        self.state = ControllerState.neutral()

    def apply(self, state):
        self.state = state

    def close(self):
        pass


class DiscoveryTests(unittest.TestCase):
    def test_discovery_protocol_matches_android(self):
        self.assertEqual(DISCOVERY_REQUEST, b"POCKETPAD_DISCOVER_V1")
        self.assertEqual(
            encode_discovery_response("Gaming Desktop", 26760),
            b"POCKETPAD_HOST_V1|Gaming Desktop|26760",
        )

    def test_invalid_response_fields_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "Invalid"):
            encode_discovery_response("Injected|Host", 26760)
        with self.assertRaisesRegex(ValueError, "Invalid"):
            encode_discovery_response("Gaming Desktop", 70000)

    def test_only_authorized_physical_usb_devices_are_selected(self):
        output = (
            "List of devices attached\n"
            "phone-123 device product:cd6 model:TECNO_CD6\n"
            "phone-unauthorized unauthorized usb:1-2\n"
            "emulator-5554 device\n"
            "192.168.1.12:5555 device\n"
        )
        self.assertEqual(usb_device_serials(output), ["phone-123"])

    def test_usb_reverse_mapping_requires_matching_device_and_both_endpoints(self):
        output = (
            "UsbFfs tcp:26762 tcp:26762\n"
            "other-phone UsbFfs tcp:26762 tcp:26762\n"
            "UsbFfs tcp:26760 tcp:26760\n"
        )
        self.assertTrue(has_usb_reverse_mapping(output, "phone-123", 26762))
        self.assertFalse(has_usb_reverse_mapping(output, "phone-123", 26761))
        self.assertFalse(
            has_usb_reverse_mapping(
                "phone-123 UsbFfs tcp:26762 tcp:26762\n",
                "other-phone",
                26762,
            )
        )

    def test_tcp_reader_reassembles_a_fragmented_controller_frame(self):
        sender, receiver = socket.socketpair()
        payload = bytes(range(PACKET_SIZE))
        try:
            sender.sendall(payload[:13])
            sender.sendall(payload[13:])
            self.assertEqual(Receiver._receive_tcp_packet(receiver), payload)
        finally:
            sender.close()
            receiver.close()

    def test_tcp_reader_keeps_partial_frame_after_socket_timeout(self):
        sender, receiver = socket.socketpair()
        receiver.settimeout(0.02)
        payload = bytes(range(PACKET_SIZE))
        pending = bytearray()
        try:
            sender.sendall(payload[:11])
            with self.assertRaises(socket.timeout):
                Receiver._receive_tcp_packet(receiver, pending)
            self.assertEqual(len(pending), 11)
            sender.sendall(payload[11:])
            self.assertEqual(Receiver._receive_tcp_packet(receiver, pending), payload)
            self.assertEqual(pending, bytearray())
        finally:
            sender.close()
            receiver.close()

    def test_companion_accepts_and_echoes_authenticated_usb_tcp_input(self):
        key = bytes(range(32))
        pad = RecordingController()
        receiver = Receiver(
            key,
            "127.0.0.1",
            queue.Queue(),
            udp_port=0,
            discovery_port=0,
            usb_port=0,
            virtual_controller_factory=lambda: pad,
        )
        receiver.start()
        try:
            client = socket.create_connection(receiver.tcp_server.server_address, timeout=2)
            client.settimeout(2)
            expected = ControllerState(0x1234, -12000, 15000, 0, 5000, 100, 240)
            packet = encode(expected, 0, time.time_ns() // 1_000_000, key)
            client.sendall(packet[:17])
            client.sendall(packet[17:])
            response = Receiver._receive_tcp_packet(client)
            self.assertEqual(response, packet)
            self.assertEqual(decode(response, key).state, expected)
            self.assertEqual(pad.state, expected)
        finally:
            if "client" in locals():
                client.close()
            receiver.close()

    def test_companion_rejects_invalid_usb_pairing_signature(self):
        key = bytes(range(32))
        receiver = Receiver(
            key,
            "127.0.0.1",
            queue.Queue(),
            udp_port=0,
            discovery_port=0,
            usb_port=0,
            virtual_controller_factory=RecordingController,
        )
        receiver.start()
        try:
            with socket.create_connection(receiver.tcp_server.server_address, timeout=2) as client:
                client.settimeout(2)
                packet = bytearray(
                    encode(ControllerState.neutral(), 0, time.time_ns() // 1_000_000, key)
                )
                packet[-1] ^= 0x01
                client.sendall(packet)
                self.assertEqual(client.recv(1), b"")
            self.assertTrue(any("Rejected invalid USB" in line for line in receiver.logs))
        finally:
            receiver.close()


if __name__ == "__main__":
    unittest.main()
