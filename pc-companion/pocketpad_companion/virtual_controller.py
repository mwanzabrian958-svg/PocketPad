"""Optional native virtual controller backends."""

from __future__ import annotations

import sys
from typing import Callable, Protocol

from .protocol import ControllerState


def xusb_axis(value: int) -> int:
    return max(-32768, min(32767, int(value)))


def xusb_trigger(value: int) -> int:
    return max(0, min(255, int(value)))


def linux_axis(value: int) -> int:
    return max(-32767, min(32767, value))


def linux_trigger(value: int) -> int:
    return max(0, min(255, value))


class VirtualController(Protocol):
    name: str

    def apply(self, state: ControllerState) -> None: ...

    def register_rumble_callback(self, callback: Callable[[int, int], None]) -> None: ...

    def close(self) -> None: ...


class WindowsXboxController:
    name = "Xbox 360 (ViGEmBus)"

    def __init__(self) -> None:
        try:
            import vgamepad as vg
        except ImportError as error:
            raise RuntimeError(
                "Windows emulation needs ViGEmBus and the pinned vgamepad package."
            ) from error
        self._vg = vg
        self._pad = vg.VX360Gamepad()
        self._buttons = (
            (0, vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP),
            (1, vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN),
            (2, vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT),
            (3, vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT),
            (4, vg.XUSB_BUTTON.XUSB_GAMEPAD_A),
            (5, vg.XUSB_BUTTON.XUSB_GAMEPAD_B),
            (6, vg.XUSB_BUTTON.XUSB_GAMEPAD_X),
            (7, vg.XUSB_BUTTON.XUSB_GAMEPAD_Y),
            (8, vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER),
            (9, vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER),
            (10, vg.XUSB_BUTTON.XUSB_GAMEPAD_START),
            (11, vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK),
            (13, vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB),
            (14, vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_THUMB),
        )

    def apply(self, state: ControllerState) -> None:
        self._pad.reset()
        for bit, button in self._buttons:
            if state.buttons & (1 << bit):
                self._pad.press_button(button=button)
        self._pad.left_joystick(x_value=xusb_axis(state.left_x), y_value=-xusb_axis(state.left_y))
        self._pad.right_joystick(x_value=xusb_axis(state.right_x), y_value=-xusb_axis(state.right_y))
        self._pad.left_trigger(value=xusb_trigger(state.left_trigger))
        self._pad.right_trigger(value=xusb_trigger(state.right_trigger))
        self._pad.update()

    def register_rumble_callback(self, callback: Callable[[int, int], None]) -> None:
        self.rumble_callback = callback
        try:
            def _notification(client, target, large_motor, small_motor, led_number):
                if hasattr(self, "rumble_callback") and self.rumble_callback:
                    self.rumble_callback(large_motor, small_motor)
            self._pad.register_notification(_notification)
        except Exception:
            pass

    def close(self) -> None:
        self.apply(ControllerState.neutral())
        self._pad.reset()
        self._pad.update()


class LinuxUinputController:
    name = "Linux uinput"

    def __init__(self) -> None:
        try:
            from evdev import AbsInfo, UInput, ecodes
        except ImportError as error:
            raise RuntimeError("Linux emulation needs the pinned evdev package and uinput access.") from error

        self._ecodes = ecodes
        self._pad = UInput(
            {
                ecodes.EV_KEY: [
                    ecodes.BTN_SOUTH, ecodes.BTN_EAST, ecodes.BTN_NORTH, ecodes.BTN_WEST,
                    ecodes.BTN_TL, ecodes.BTN_TR, ecodes.BTN_START, ecodes.BTN_SELECT,
                    ecodes.BTN_THUMBL, ecodes.BTN_THUMBR, ecodes.BTN_DPAD_UP,
                    ecodes.BTN_DPAD_DOWN, ecodes.BTN_DPAD_LEFT, ecodes.BTN_DPAD_RIGHT,
                ],
                ecodes.EV_ABS: {
                    ecodes.ABS_X: AbsInfo(0, -32767, 32767, 0, 0, 0),
                    ecodes.ABS_Y: AbsInfo(0, -32767, 32767, 0, 0, 0),
                    ecodes.ABS_RX: AbsInfo(0, -32767, 32767, 0, 0, 0),
                    ecodes.ABS_RY: AbsInfo(0, -32767, 32767, 0, 0, 0),
                    ecodes.ABS_Z: AbsInfo(0, 0, 255, 0, 0, 0),
                    ecodes.ABS_RZ: AbsInfo(0, 0, 255, 0, 0, 0),
                },
            },
            name="PocketPad",
            vendor=0x1209,
            product=0x5050,
        )
        self._buttons = (
            (0, ecodes.BTN_DPAD_UP), (1, ecodes.BTN_DPAD_DOWN),
            (2, ecodes.BTN_DPAD_LEFT), (3, ecodes.BTN_DPAD_RIGHT),
            (4, ecodes.BTN_SOUTH), (5, ecodes.BTN_EAST),
            (6, ecodes.BTN_WEST), (7, ecodes.BTN_NORTH),
            (8, ecodes.BTN_TL), (9, ecodes.BTN_TR),
            (10, ecodes.BTN_START), (11, ecodes.BTN_SELECT),
            (13, ecodes.BTN_THUMBL), (14, ecodes.BTN_THUMBR),
        )

    def apply(self, state: ControllerState) -> None:
        for bit, code in self._buttons:
            self._pad.write(self._ecodes.EV_KEY, code, int(bool(state.buttons & (1 << bit))))
        for axis, value in (
            (self._ecodes.ABS_X, linux_axis(state.left_x)),
            (self._ecodes.ABS_Y, linux_axis(state.left_y)),
            (self._ecodes.ABS_RX, linux_axis(state.right_x)),
            (self._ecodes.ABS_RY, linux_axis(state.right_y)),
            (self._ecodes.ABS_Z, linux_trigger(state.left_trigger)),
            (self._ecodes.ABS_RZ, linux_trigger(state.right_trigger)),
        ):
            self._pad.write(self._ecodes.EV_ABS, axis, value)
        self._pad.syn()

    def register_rumble_callback(self, callback: Callable[[int, int], None]) -> None:
        self.rumble_callback = callback

    def close(self) -> None:
        self.apply(ControllerState.neutral())
        self._pad.close()


class MacGameController:
    name = "Apple GameController (macOS)"

    def __init__(self) -> None:
        try:
            import objc
            from GameController import GCController
        except ImportError as error:
            raise RuntimeError(
                "macOS emulation needs pyobjc and pyobjc-framework-GameController."
            ) from error
        self._gc = GCController
        self._controller = None
        if hasattr(GCController, "supportsWirelessController") and GCController.supportsWirelessController():
            try:
                self._controller = GCController.controllerWithMicroGamepad()
            except Exception:
                pass
        if not self._controller:
            controllers = GCController.controllers()
            if controllers:
                self._controller = controllers[0]

    def apply(self, state: ControllerState) -> None:
        if not self._controller:
            return
        profile = getattr(self._controller, "extendedGamepad", None) or getattr(self._controller, "microGamepad", None)
        if not profile:
            return
        if hasattr(profile, "leftThumbstick") and profile.leftThumbstick():
            profile.leftThumbstick().setXAxis_(max(-1.0, min(1.0, state.left_x / 32767.0)))
            profile.leftThumbstick().setYAxis_(max(-1.0, min(1.0, state.left_y / 32767.0)))
        if hasattr(profile, "rightThumbstick") and profile.rightThumbstick():
            profile.rightThumbstick().setXAxis_(max(-1.0, min(1.0, state.right_x / 32767.0)))
            profile.rightThumbstick().setYAxis_(max(-1.0, min(1.0, state.right_y / 32767.0)))

    def register_rumble_callback(self, callback: Callable[[int, int], None]) -> None:
        self.rumble_callback = callback

    def close(self) -> None:
        self.apply(ControllerState.neutral())


def create_virtual_controller() -> VirtualController:
    if sys.platform == "win32":
        return WindowsXboxController()
    if sys.platform.startswith("linux"):
        return LinuxUinputController()
    if sys.platform == "darwin":
        return MacGameController()
    raise RuntimeError("This companion build has no virtual controller support for this platform.")
