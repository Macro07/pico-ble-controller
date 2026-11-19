from adafruit_hid.keyboard_layout_us import KeyboardLayoutUS


class KeyboardLayoutIT(KeyboardLayoutUS):
    """Layout tastiera Italiana (Windows/Linux standard) per Adafruit HID."""

    # Mappatura ASCII -> (Keycode, Shift_necessario, AltGr_necessario)
    # Basata sui keycode fisici US inviati ad un OS configurato in IT
    ASCII_TO_KEYCODE = (
        b"\x00"  # NUL
        b"\x00"  # SOH
        b"\x00"  # STX
        b"\x00"  # ETX
        b"\x00"  # EOT
        b"\x00"  # ENQ
        b"\x00"  # ACK
        b"\x00"  # BEL
        b"\x2a"  # BS  (Backspace)
        b"\x2b"  # TAB
        b"\x28"  # LF  (Enter)
        b"\x00"  # VT
        b"\x00"  # FF
        b"\x00"  # CR
        b"\x00"  # SO
        b"\x00"  # SI
        b"\x00"  # DLE
        b"\x00"  # DC1
        b"\x00"  # DC2
        b"\x00"  # DC3
        b"\x00"  # DC4
        b"\x00"  # NAK
        b"\x00"  # SYN
        b"\x00"  # ETB
        b"\x00"  # CAN
        b"\x00"  # EM
        b"\x00"  # SUB
        b"\x29"  # ESC
        b"\x00"  # FS
        b"\x00"  # GS
        b"\x00"  # RS
        b"\x00"  # US
        b"\x2c"  # ' ' (Spazio)
        b"\x9e"  # !   (Shift + 1)
        b"\x9f"  # "   (Shift + 2)
        b"\x00"  # #   (Gestito via AltGr in write)
        b"\xa1"  # $   (Shift + 4)
        b"\xa2"  # %   (Shift + 5)
        b"\xa3"  # &   (Shift + 6)
        b"\x2d"  # '   (Tasto - su US)
        b"\xa5"  # (   (Shift + 8)
        b"\xa6"  # )   (Shift + 9)
        b"\xb0"  # *   (Shift + ])
        b"\x30"  # +   (Tasto ] su US)
        b"\x36"  # ,   (Tasto , su US)
        b"\x38"  # -   (Tasto / su US)
        b"\x37"  # .   (Tasto . su US)
        b"\xa4"  # /   (Shift + 7)
        b"\x27"  # 0
        b"\x1e"  # 1
        b"\x1f"  # 2
        b"\x20"  # 3
        b"\x21"  # 4
        b"\x22"  # 5
        b"\x23"  # 6
        b"\x24"  # 7
        b"\x25"  # 8
        b"\x26"  # 9
        b"\xb7"  # :   (Shift + .)
        b"\xb6"  # ;   (Shift + ,)
        b"\x64"  # <   (Tasto ISO <> accanto a Shift sx)
        b"\xa7"  # =   (Shift + 0)
        b"\xe4"  # >   (Shift + tasto ISO <>)
        b"\xad"  # ?   (Shift + ')
        b"\x00"  # @   (Gestito via AltGr in write)
        b"\x84"  # A
        b"\x85"  # B
        b"\x86"  # C
        b"\x87"  # D
        b"\x88"  # E
        b"\x89"  # F
        b"\x8a"  # G
        b"\x8b"  # H
        b"\x8c"  # I
        b"\x8d"  # J
        b"\x8e"  # K
        b"\x8f"  # L
        b"\x90"  # M
        b"\x91"  # N
        b"\x92"  # O
        b"\x93"  # P
        b"\x94"  # Q
        b"\x95"  # R
        b"\x96"  # S
        b"\x97"  # T
        b"\x98"  # U
        b"\x99"  # V
        b"\x9a"  # W
        b"\x9b"  # X
        b"\x9c"  # Y
        b"\x9d"  # Z
        b"\x00"  # [   (Gestito via AltGr in write)
        b"\x35"  # \   (Tasto ` su US, sinistra dell'1)
        b"\x00"  # ]   (Gestito via AltGr in write)
        b"\xae"  # ^   (Shift + ì)
        b"\xb8"  # _   (Shift + -)
        b"\x00"  # `
        b"\x04"  # a
        b"\x05"  # b
        b"\x06"  # c
        b"\x07"  # d
        b"\x08"  # e
        b"\x09"  # f
        b"\x0a"  # g
        b"\x0b"  # h
        b"\x0c"  # i
        b"\x0d"  # j
        b"\x0e"  # k
        b"\x0f"  # l
        b"\x10"  # m
        b"\x11"  # n
        b"\x12"  # o
        b"\x13"  # p
        b"\x14"  # q
        b"\x15"  # r
        b"\x16"  # s
        b"\x17"  # t
        b"\x18"  # u
        b"\x19"  # v
        b"\x1a"  # w
        b"\x1b"  # x
        b"\x1c"  # y
        b"\x1d"  # z
        b"\x00"  # {   (Gestito via AltGr in write)
        b"\xb5"  # |   (Shift + \)
        b"\x00"  # }   (Gestito via AltGr in write)
        b"\x00"  # ~
        b"\x4c"  # DEL
    )

    # Caratteri che richiedono AltGr (Right Alt = 0xE6) su tastiera italiana
    ALTGR_MAP = {
        "@": 0x33,  # AltGr + ò (tasto ; su US)
        "#": 0x34,  # AltGr + à (tasto ' su US)
        "[": 0x2F,  # AltGr + è (tasto [ su US)
        "]": 0x30,  # AltGr + + (tasto ] su US)
    }

    ALTGR_SHIFT_MAP = {
        "{": 0x2F,  # AltGr + Shift + è
        "}": 0x30,  # AltGr + Shift + +
    }

    def write(self, string):
        for char in string:
            if char in self.ALTGR_MAP:
                self.keyboard.press(0xE6, self.ALTGR_MAP[char])
                self.keyboard.release_all()
            elif char in self.ALTGR_SHIFT_MAP:
                self.keyboard.press(0xE6, 0xE1, self.ALTGR_SHIFT_MAP[char])
                self.keyboard.release_all()
            else:
                super().write(char)