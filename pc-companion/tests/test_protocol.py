import unittest

from pocketpad_companion.protocol import (
    PACKET_SIZE,
    ControllerState,
    decode,
    encode,
)
from pocketpad_companion.virtual_controller import linux_axis, linux_trigger, xusb_axis, xusb_trigger


class ProtocolTests(unittest.TestCase):
    def setUp(self):
        self.key = bytes(range(32))
        self.now = 1_700_000_000_000

    def test_full_state_round_trip(self):
        expected = ControllerState(0x1234, -32767, 16383, 8191, 32767, 191, 255)
        packet = encode(expected, 42, self.now, self.key)
        self.assertEqual(len(packet), PACKET_SIZE)
        frame = decode(packet, self.key, now_ms=self.now)
        self.assertEqual(frame.sequence, 42)
        self.assertEqual(frame.timestamp_ms, self.now)
        self.assertEqual(frame.state, expected)

    def test_modified_packet_is_rejected(self):
        packet = bytearray(encode(ControllerState.neutral(), 1, self.now, self.key))
        packet[30] ^= 1
        with self.assertRaisesRegex(ValueError, "signature"):
            decode(bytes(packet), self.key, now_ms=self.now)

    def test_stale_packet_is_rejected(self):
        packet = encode(ControllerState.neutral(), 1, self.now, self.key)
        with self.assertRaisesRegex(ValueError, "Stale"):
            decode(packet, self.key, now_ms=self.now + 6000)

    def test_virtual_controller_values_use_native_integer_ranges(self):
        self.assertEqual(xusb_axis(-32767), -32767)
        self.assertEqual(xusb_axis(32767), 32767)
        self.assertEqual(xusb_trigger(191), 191)
        self.assertEqual(linux_axis(-32767), -32767)
        self.assertEqual(linux_axis(32767), 32767)
        self.assertEqual(linux_trigger(255), 255)


if __name__ == "__main__":
    unittest.main()
