import time
import board
import busio
import usb_hid
from adafruit_hid.keyboard import Keyboard
from adafruit_hid.keyboard_layout_us import KeyboardLayoutUS
from adafruit_hid.keycode import Keycode

# Inizializzazione Tastiera USB HID (Layout predefinito: US)
kbd = Keyboard(usb_hid.devices)
layout = KeyboardLayoutUS(kbd)
current_layout = "US"

# Inizializzazione UART0 verso ESP32-C3 (GP0 = TX, GP1 = RX)
uart = busio.UART(board.GP0, board.GP1, baudrate=115200, timeout=0.05)

# Stato del sistema: "CMD" (attesa comandi) o "LIVE" (digitazione diretta)
system_mode = "CMD"

# Mappatura dei moduli layout disponibili (da inserire in /lib/adafruit_hid/)
LAYOUT_MODULES = {
    "US": ("adafruit_hid.keyboard_layout_us", "KeyboardLayoutUS"),
    "IT": ("keyboard_layout_it", "KeyboardLayoutIT"),
}

# Mappatura tasti speciali per //SHORTCUT e modalità LIVE
KEY_MAP = {
    "CTRL": Keycode.CONTROL,
    "SHIFT": Keycode.SHIFT,
    "ALT": Keycode.ALT,
    "GUI": Keycode.GUI,
    "WIN": Keycode.GUI,
    "CMD": Keycode.GUI,
    "ENTER": Keycode.ENTER,
    "ESC": Keycode.ESCAPE,
    "TAB": Keycode.TAB,
    "BACKSPACE": Keycode.BACKSPACE,
    "SPACE": Keycode.SPACEBAR,
    "UP": Keycode.UP_ARROW,
    "DOWN": Keycode.DOWN_ARROW,
    "LEFT": Keycode.LEFT_ARROW,
    "RIGHT": Keycode.RIGHT_ARROW,
    "DEL": Keycode.DELETE,
    "C": Keycode.C,
    "V": Keycode.V,
    "X": Keycode.X,
    "Z": Keycode.Z,
    "A": Keycode.A,
    "S": Keycode.S,
    "R": Keycode.R,
    "F4": Keycode.F4,
}


def send_ack(msg="#"):
    """Invia conferma di ricezione all'ESP32/App."""
    uart.write((msg + "\n").encode("utf-8"))


def handle_output(text, target):
    """Gestisce la destinazione dei log in base al tag -output."""
    if target == "none":
        return
    elif target == "pico":
        try:
            with open("log_output.txt", "a") as f:
                f.write(text + "\n")
        except OSError:
            send_ack("ERR:Flash_ReadOnly")
    elif target == "phone":
        uart.write((text + "\n").encode("utf-8"))


def parse_flags(raw_args):
    """Estrae eventuali flag (es. -output pico) e restituisce (testo_pulito, flags)."""
    flags = {"output": "none", "file": None}
    tokens = raw_args.split(" ")
    clean_tokens = []
    i = 0
    while i < len(tokens):
        if tokens[i] == "-output" and i + 1 < len(tokens):
            flags["output"] = tokens[i + 1].lower()
            i += 2
        elif tokens[i] == "-file" and i + 1 < len(tokens):
            flags["file"] = tokens[i + 1]
            i += 2
        else:
            clean_tokens.append(tokens[i])
            i += 1
    return " ".join(clean_tokens).strip(), flags


def set_keyboard_layout(lang_code):
    """Cambia dinamicamente il layout della tastiera HID."""
    global layout, current_layout
    code = lang_code.strip().upper()
    if code not in LAYOUT_MODULES:
        return False, f"ERR:Unsupported_Layout_{code}"

    module_name, class_name = LAYOUT_MODULES[code]
    try:
        mod = __import__(module_name, None, None, [class_name])
        layout_cls = getattr(mod, class_name)
        layout = layout_cls(kbd)
        current_layout = code
        return True, f"OK:LAYOUT {code}"
    except ImportError:
        return False, f"ERR:Missing_Lib_{module_name}"


def execute_shortcut(combo_str):
    """Decodifica ed esegue combinazioni come CTRL+C o WIN+R."""
    keys = [k.strip().upper() for k in combo_str.split("+")]
    keycodes = []
    for k in keys:
        if k in KEY_MAP:
            keycodes.append(KEY_MAP[k])
        elif len(k) == 1 and hasattr(Keycode, k):
            keycodes.append(getattr(Keycode, k))

    if keycodes:
        kbd.press(*keycodes)
        time.sleep(0.05)
        kbd.release_all()


def execute_write(text, file_name=None):
    """Scrive un blocco di testo o il contenuto di un file locale sul Pico."""
    if file_name:
        try:
            with open(file_name, "r") as f:
                for line in f:
                    layout.write(line+'\n')
                    time.sleep(0.01)
        except OSError:
            send_ack("ERR:File_Not_Found")
    elif text:
        layout.write(text+'\n')


def execute_link(url):
    """Apre la finestra Esegui (WIN+R) e lancia l'URL nel browser predefinito."""
    if not url:
        return
    execute_shortcut("WIN+R")
    time.sleep(0.3)
    layout.write(url + "\n")


def emergency_stop():
    """Rilascia tutti i tasti e riporta il sistema in modalità comandi."""
    global system_mode
    kbd.release_all()
    system_mode = "CMD"
    send_ack("#STOPPED")


print(f"Pico HID Controller avviato (Layout: {current_layout}) in modalità CMD.")

while True:
    data = uart.readline()
    if not data:
        time.sleep(0.01)
        continue

    msg = data.decode("utf-8").strip()
    if not msg:
        continue

    # 1. Priorità assoluta al comando di interruzione
    if msg.upper() == "//STOP":
        emergency_stop()
        continue

    # 2. Gestione modalità LIVE (//KEYBOARD attiva)
    if system_mode == "LIVE":
        send_ack("#")
        if msg.startswith("S:"):
            execute_shortcut(msg[2:])
        elif msg.startswith("K:"):
            layout.write(msg[2:])
        else:
            layout.write(msg)
        continue

    # 3. Gestione modalità COMANDI (prefisso //)
    if msg.startswith("//"):
        send_ack("#")
        parts = msg.split(" ", 1)
        cmd = parts[0].upper()
        raw_args = parts[1] if len(parts) > 1 else ""
        clean_arg, flags = parse_flags(raw_args)

        if cmd == "//KEYBOARD":
            system_mode = "LIVE"
            handle_output("MODE:LIVE", flags["output"])

        elif cmd == "//LAYOUT":
            success, status_msg = set_keyboard_layout(clean_arg)
            if not success:
                send_ack(status_msg)
            else:
                handle_output(status_msg, flags["output"])

        elif cmd == "//SHORTCUT":
            execute_shortcut(clean_arg)
            handle_output(f"OK:SHORTCUT {clean_arg}", flags["output"])

        elif cmd == "//WRITE":
            execute_write(clean_arg, file_name=flags["file"])
            handle_output("OK:WRITE", flags["output"])

        elif cmd == "//LINK":
            execute_link(clean_arg)
            handle_output(f"OK:LINK {clean_arg}", flags["output"])

        else:
            send_ack("ERR:Unknown_Command")

    time.sleep(0.01)
