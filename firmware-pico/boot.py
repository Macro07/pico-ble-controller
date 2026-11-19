import storage
import usb_cdc
import usb_hid

# 1. Abilita la scrittura sulla memoria interna da codice (per -output pico)
# Nota: quando attivo, il PC vedrà il drive CIRCUITPY in sola lettura.
storage.remount("/", readonly=False)

# 2. Abilita la seconda porta seriale virtuale (usb_cdc.data)
# Serve per ricevere silenziosamente l'output degli script lanciati sul PC
usb_cdc.enable(console=True, data=True)

# 3. Mantiene attiva solo la tastiera HID (disabilita mouse/gamepad per pulizia)
usb_hid.enable((usb_hid.Device.KEYBOARD,))