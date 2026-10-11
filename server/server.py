#!/usr/bin/env python3
"""
BlanketCozzy - TrackPad Companion (macOS & Windows)
Your Mac & PC, comfortably in control. Native input over local Wi-Fi.
Features automatic Keynote / PowerPoint / Slides detection!
"""

import sys
import socket
import threading
import platform
import time
import json

OS_TYPE = platform.system()  # 'Darwin' for macOS, 'Windows' for Windows, 'Linux' for Linux
PORT = 8765
DISCOVERY_PORT = 8766
BUFFER_SIZE = 1024

# =====================================================================
# Native OS Input & Window Controllers (Zero-Dependency)
# =====================================================================

if OS_TYPE == "Darwin":
    import ctypes
    from ctypes import c_void_p
    import subprocess

    class CGPoint(ctypes.Structure):
        _fields_ = [('x', ctypes.c_double), ('y', ctypes.c_double)]

    CG = ctypes.cdll.LoadLibrary('/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics')

    CG.CGEventCreate.restype = c_void_p
    CG.CGEventCreate.argtypes = [c_void_p]

    CG.CGEventGetLocation.restype = CGPoint
    CG.CGEventGetLocation.argtypes = [c_void_p]

    CG.CGEventCreateMouseEvent.restype = c_void_p
    CG.CGEventCreateMouseEvent.argtypes = [c_void_p, ctypes.c_uint32, CGPoint, ctypes.c_uint32]

    CG.CGEventCreateScrollWheelEvent.restype = c_void_p
    CG.CGEventCreateScrollWheelEvent.argtypes = [c_void_p, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_int32]

    CG.CGEventCreateKeyboardEvent.restype = c_void_p
    CG.CGEventCreateKeyboardEvent.argtypes = [c_void_p, ctypes.c_uint16, ctypes.c_bool]

    CG.CGEventKeyboardSetUnicodeString.argtypes = [c_void_p, ctypes.c_uint32, ctypes.c_wchar_p]

    CG.CGEventPost.restype = None
    CG.CGEventPost.argtypes = [ctypes.c_uint32, c_void_p]

    CFRelease = CG.CFRelease
    CFRelease.restype = None
    CFRelease.argtypes = [c_void_p]

    _last_frontmost_cache = ("", 0)

    def get_frontmost_app():
        global _last_frontmost_cache
        now = time.time()
        if now - _last_frontmost_cache[1] < 1.0:
            return _last_frontmost_cache[0]
        try:
            cmd = ['osascript', '-e', 'tell application "System Events" to get name of first application process whose frontmost is true']
            res = subprocess.check_output(cmd, timeout=0.8).decode('utf-8', errors='ignore').strip()
            _last_frontmost_cache = (res, now)
            return res
        except Exception:
            return _last_frontmost_cache[0]

    # CGEvent constants
    kCGHIDEventTap = 0
    kCGEventLeftMouseDown = 1
    kCGEventLeftMouseUp = 2
    kCGEventRightMouseDown = 3
    kCGEventRightMouseUp = 4
    kCGEventMouseMoved = 5
    kCGEventLeftMouseDragged = 6
    kCGMouseButtonLeft = 0
    kCGMouseButtonRight = 1

    class NativeController:
        def __init__(self):
            self.is_left_down = False
            self.last_pos = (500.0, 500.0)

        def get_pos(self):
            try:
                evt = CG.CGEventCreate(None)
                if not evt:
                    return self.last_pos
                pt = CG.CGEventGetLocation(evt)
                CFRelease(evt)
                self.last_pos = (pt.x, pt.y)
                return pt.x, pt.y
            except Exception:
                return self.last_pos

        def move(self, dx, dy):
            try:
                x, y = self.get_pos()
                new_pt = CGPoint(x + dx, y + dy)
                evt_type = kCGEventLeftMouseDragged if self.is_left_down else kCGEventMouseMoved
                evt = CG.CGEventCreateMouseEvent(None, evt_type, new_pt, kCGMouseButtonLeft)
                if evt:
                    CG.CGEventPost(kCGHIDEventTap, evt)
                    CFRelease(evt)
                    self.last_pos = (x + dx, y + dy)
            except Exception:
                pass

        def click_left(self):
            try:
                x, y = self.get_pos()
                pt = CGPoint(x, y)
                down = CG.CGEventCreateMouseEvent(None, kCGEventLeftMouseDown, pt, kCGMouseButtonLeft)
                up = CG.CGEventCreateMouseEvent(None, kCGEventLeftMouseUp, pt, kCGMouseButtonLeft)
                if down:
                    CG.CGEventPost(kCGHIDEventTap, down)
                    CFRelease(down)
                if up:
                    CG.CGEventPost(kCGHIDEventTap, up)
                    CFRelease(up)
            except Exception:
                pass

        def click_right(self):
            try:
                x, y = self.get_pos()
                pt = CGPoint(x, y)
                down = CG.CGEventCreateMouseEvent(None, kCGEventRightMouseDown, pt, kCGMouseButtonRight)
                up = CG.CGEventCreateMouseEvent(None, kCGEventRightMouseUp, pt, kCGMouseButtonRight)
                if down:
                    CG.CGEventPost(kCGHIDEventTap, down)
                    CFRelease(down)
                if up:
                    CG.CGEventPost(kCGHIDEventTap, up)
                    CFRelease(up)
            except Exception:
                pass

        def mouse_down(self, button="left"):
            try:
                x, y = self.get_pos()
                pt = CGPoint(x, y)
                if button == "right":
                    evt = CG.CGEventCreateMouseEvent(None, kCGEventRightMouseDown, pt, kCGMouseButtonRight)
                else:
                    self.is_left_down = True
                    evt = CG.CGEventCreateMouseEvent(None, kCGEventLeftMouseDown, pt, kCGMouseButtonLeft)
                if evt:
                    CG.CGEventPost(kCGHIDEventTap, evt)
                    CFRelease(evt)
            except Exception:
                pass

        def mouse_up(self, button="left"):
            try:
                x, y = self.get_pos()
                pt = CGPoint(x, y)
                if button == "right":
                    evt = CG.CGEventCreateMouseEvent(None, kCGEventRightMouseUp, pt, kCGMouseButtonRight)
                else:
                    self.is_left_down = False
                    evt = CG.CGEventCreateMouseEvent(None, kCGEventLeftMouseUp, pt, kCGMouseButtonLeft)
                if evt:
                    CG.CGEventPost(kCGHIDEventTap, evt)
                    CFRelease(evt)
            except Exception:
                pass

        def scroll(self, dx, dy):
            try:
                steps_y = int(dy)
                if steps_y != 0:
                    evt = CG.CGEventCreateScrollWheelEvent(None, 0, 1, steps_y)
                    if evt:
                        CG.CGEventPost(kCGHIDEventTap, evt)
                        CFRelease(evt)
            except Exception:
                pass

        def type_text(self, text):
            try:
                for char in text:
                    down = CG.CGEventCreateKeyboardEvent(None, 0, True)
                    up = CG.CGEventCreateKeyboardEvent(None, 0, False)
                    if down and up:
                        CG.CGEventKeyboardSetUnicodeString(down, len(char), char)
                        CG.CGEventKeyboardSetUnicodeString(up, len(char), char)
                        CG.CGEventPost(kCGHIDEventTap, down)
                        CG.CGEventPost(kCGHIDEventTap, up)
                        CFRelease(down)
                        CFRelease(up)
            except Exception:
                pass

        def press_key(self, key_name):
            try:
                key_codes = {
                    "enter": 36,
                    "backspace": 51,
                    "space": 49,
                    "tab": 48,
                    "esc": 53,
                    "up": 126,
                    "down": 125,
                    "left": 123,
                    "right": 124,
                    "f5": 96
                }
                code = key_codes.get(key_name.lower())
                if code is not None:
                    down = CG.CGEventCreateKeyboardEvent(None, code, True)
                    up = CG.CGEventCreateKeyboardEvent(None, code, False)
                    if down and up:
                        CG.CGEventPost(kCGHIDEventTap, down)
                        CG.CGEventPost(kCGHIDEventTap, up)
                        CFRelease(down)
                        CFRelease(up)
            except Exception:
                pass


elif OS_TYPE == "Windows":
    import ctypes

    user32 = ctypes.windll.user32

    MOUSEEVENTF_MOVE = 0x0001
    MOUSEEVENTF_LEFTDOWN = 0x0002
    MOUSEEVENTF_LEFTUP = 0x0004
    MOUSEEVENTF_RIGHTDOWN = 0x0008
    MOUSEEVENTF_RIGHTUP = 0x0010
    MOUSEEVENTF_WHEEL = 0x0800

    VK_MAP = {
        "left": 0x25,
        "up": 0x26,
        "right": 0x27,
        "down": 0x28,
        "space": 0x20,
        "enter": 0x0D,
        "backspace": 0x08,
        "esc": 0x1B,
        "f5": 0x74
    }

    def get_frontmost_app():
        try:
            hwnd = user32.GetForegroundWindow()
            length = user32.GetWindowTextLengthW(hwnd)
            buff = ctypes.create_unicode_buffer(length + 1)
            user32.GetWindowTextW(hwnd, buff, length + 1)
            return buff.value
        except Exception:
            return ""

    class NativeController:
        def __init__(self):
            self.is_left_down = False

        def move(self, dx, dy):
            user32.mouse_event(MOUSEEVENTF_MOVE, int(dx), int(dy), 0, 0)

        def click_left(self):
            user32.mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
            user32.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)

        def click_right(self):
            user32.mouse_event(MOUSEEVENTF_RIGHTDOWN, 0, 0, 0, 0)
            user32.mouse_event(MOUSEEVENTF_RIGHTUP, 0, 0, 0, 0)

        def mouse_down(self, button="left"):
            flag = MOUSEEVENTF_RIGHTDOWN if button == "right" else MOUSEEVENTF_LEFTDOWN
            user32.mouse_event(flag, 0, 0, 0, 0)

        def mouse_up(self, button="left"):
            flag = MOUSEEVENTF_RIGHTUP if button == "right" else MOUSEEVENTF_LEFTUP
            user32.mouse_event(flag, 0, 0, 0, 0)

        def scroll(self, dx, dy):
            user32.mouse_event(MOUSEEVENTF_WHEEL, 0, 0, int(dy * 20), 0)

        def type_text(self, text):
            pass

        def press_key(self, key_name):
            vk = VK_MAP.get(key_name.lower())
            if vk:
                user32.keybd_event(vk, 0, 0, 0)
                user32.keybd_event(vk, 0, 2, 0)

else:
    def get_frontmost_app(): return ""
    class NativeController:
        def move(self, dx, dy): pass
        def click_left(self): pass
        def click_right(self): pass
        def mouse_down(self, btn="left"): pass
        def mouse_up(self, btn="left"): pass
        def scroll(self, dx, dy): pass
        def type_text(self, text): pass
        def press_key(self, key): pass

controller = NativeController()

def is_presentation_active():
    """Detects if Keynote, PowerPoint, Google Slides, or PDF presentation is currently in focus."""
    app_name = get_frontmost_app().lower()
    presentation_keywords = ["keynote", "powerpoint", "slides", "impress", "acrobat", "wps presentation"]
    return any(keyword in app_name for keyword in presentation_keywords)

# =====================================================================
# Networking & Server Logic
# =====================================================================

def get_local_ip():
    """Detects local LAN/Wi-Fi IP address."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
    except Exception:
        ip = '127.0.0.1'
    finally:
        s.close()
    return ip

def get_broadcast_ip(local_ip):
    parts = local_ip.split('.')
    if len(parts) == 4:
        return f"{parts[0]}.{parts[1]}.{parts[2]}.255"
    return "255.255.255.255"

def start_discovery_beacon(local_ip, port):
    """Broadcasts discovery beacons over local Wi-Fi and replies to discovery queries."""
    def worker():
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            if hasattr(socket, 'SO_REUSEPORT'):
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.bind(('0.0.0.0', DISCOVERY_PORT))
            sock.settimeout(1.5)

            computer_name = socket.gethostname().replace('.local', '')
            os_display = "macOS" if OS_TYPE == "Darwin" else ("Windows" if OS_TYPE == "Windows" else OS_TYPE)
            bcast_ip = get_broadcast_ip(local_ip)

            payload = json.dumps({
                "service": "remote_trackpad",
                "name": computer_name,
                "ip": local_ip,
                "port": port,
                "os": os_display
            }).encode('utf-8')

            last_broadcast = 0
            while True:
                now = time.time()
                if now - last_broadcast > 2.0:
                    try:
                        sock.sendto(payload, (bcast_ip, DISCOVERY_PORT))
                    except Exception:
                        pass
                    try:
                        sock.sendto(payload, ("255.255.255.255", DISCOVERY_PORT))
                    except Exception:
                        pass
                    last_broadcast = now

                # 2. Check for discovery probe from Android app
                try:
                    data, addr = sock.recvfrom(1024)
                    if data and b"DISCOVER" in data:
                        sock.sendto(payload, addr)
                except socket.timeout:
                    pass
                except Exception:
                    pass
        except Exception as e:
            print(f"[!] Auto-discovery error: {e}")

    t = threading.Thread(target=worker, daemon=True)
    t.start()

def print_banner(ip, port):
    print("=" * 65)
    print("🛌 BlanketCozzy - TrackPad Desktop Companion")
    print("   Your Mac & PC, comfortably in control.")
    print("=" * 65)
    print(f"OS             : {platform.system()} ({platform.release()})")
    print(f"Server Address : {ip}:{port}")
    print(f"Auto-Discovery : Active (UDP Beacon on {DISCOVERY_PORT})")
    print("-" * 65)
    print("👉 Mobile App Auto-Connect:")
    print("   Open your Android app — it will detect this computer automatically!")
    print(f"   (Manual Address: {ip}:{port})")
    print("-" * 65)
    print("📽️ Presentation Smart Feature Active:")
    print("   When Keynote or PowerPoint is open:")
    print("   - Vol Up   -> NEXT Slide (Right Arrow)")
    print("   - Vol Down -> PREVIOUS Slide (Left Arrow)")
    print("   Otherwise (Normal Apps):")
    print("   - Vol Up   -> Left Click")
    print("   - Vol Down -> Right Click")
    print("-" * 65)
    print("Listening for incoming Wi-Fi connection...\n")

def handle_client(conn, addr):
    print(f"[+] Connected to Android client at {addr[0]}:{addr[1]}")
    if client_status_callback:
        try:
            client_status_callback(f"● Connected: {addr[0]}")
        except Exception:
            pass
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

    buffer = ""
    try:
        while True:
            data = conn.recv(BUFFER_SIZE).decode('utf-8', errors='ignore')
            if not data:
                break
            
            buffer += data
            while '\n' in buffer:
                line, buffer = buffer.split('\n', 1)
                line = line.strip()
                if not line:
                    continue
                
                parts = line.split()
                cmd = parts[0].upper()

                try:
                    if cmd == "MOVE" and len(parts) >= 3:
                        dx = float(parts[1])
                        dy = float(parts[2])
                        controller.move(dx, dy)

                    elif cmd == "TAP" or cmd == "CLICK_LEFT":
                        controller.click_left()

                    elif cmd == "R_TAP" or cmd == "CLICK_RIGHT":
                        controller.click_right()

                    # Smart Volume Up / Down Handling:
                    elif cmd == "VOL_UP":
                        if is_presentation_active():
                            # Next Slide
                            controller.press_key("right")
                        else:
                            controller.click_left()

                    elif cmd == "VOL_DOWN":
                        if is_presentation_active():
                            # Previous Slide
                            controller.press_key("left")
                        else:
                            controller.click_right()

                    # Explicit Slide Navigation Commands:
                    elif cmd == "NEXT_SLIDE":
                        controller.press_key("right")

                    elif cmd == "PREV_SLIDE":
                        controller.press_key("left")

                    elif cmd == "DOWN":
                        btn = "right" if (len(parts) > 1 and parts[1].upper() == "RIGHT") else "left"
                        controller.mouse_down(btn)

                    elif cmd == "UP":
                        btn = "right" if (len(parts) > 1 and parts[1].upper() == "RIGHT") else "left"
                        controller.mouse_up(btn)

                    elif cmd == "SCROLL" and len(parts) >= 3:
                        dx = float(parts[1])
                        dy = float(parts[2])
                        controller.scroll(dx, dy)

                    elif cmd == "TEXT" and len(parts) >= 2:
                        text = " ".join(parts[1:])
                        controller.type_text(text)

                    elif cmd == "KEY" and len(parts) >= 2:
                        controller.press_key(parts[1])

                except Exception:
                    pass

    except (ConnectionResetError, BrokenPipeError):
        pass
    finally:
        try:
            controller.mouse_up("left")
        except Exception:
            pass
        conn.close()
        print(f"[-] Client {addr[0]} disconnected. Waiting for reconnection...\n")
        if client_status_callback:
            try:
                client_status_callback("Waiting for mobile connection...")
            except Exception:
                pass

client_status_callback = None

def run_gui(ip, port, shutdown_func):
    try:
        import tkinter as tk

        root = tk.Tk()
        root.title("BlanketCozzy Companion")
        root.geometry("380x300")
        root.resizable(False, False)
        root.configure(bg="#F0F4F9")

        container = tk.Frame(root, bg="#FFFFFF", bd=1, relief="solid", highlightthickness=0)
        container.place(relx=0.5, rely=0.5, anchor="center", width=340, height=260)

        title_label = tk.Label(container, text="BlanketCozzy", font=("Helvetica", 16, "bold"), bg="#FFFFFF", fg="#0F172A")
        title_label.pack(pady=(18, 2))

        sub_label = tk.Label(container, text="TrackPad Desktop Companion", font=("Helvetica", 9), bg="#FFFFFF", fg="#64748B")
        sub_label.pack(pady=(0, 14))

        badge_frame = tk.Frame(container, bg="#EBF1F7", padx=10, pady=6)
        badge_frame.pack(fill="x", padx=20, pady=4)

        status_dot = tk.Label(badge_frame, text="●", font=("Helvetica", 11), bg="#EBF1F7", fg="#10B981")
        status_dot.pack(side="left", padx=(4, 6))

        status_text = tk.Label(badge_frame, text=f"Server Active · {ip}:{port}", font=("Courier", 10, "bold"), bg="#EBF1F7", fg="#0F172A")
        status_text.pack(side="left")

        conn_label = tk.Label(container, text="Waiting for mobile connection...", font=("Helvetica", 9), bg="#FFFFFF", fg="#64748B")
        conn_label.pack(pady=12)

        global client_status_callback
        def update_client_status(msg):
            try:
                conn_label.config(text=msg)
            except Exception:
                pass
        client_status_callback = update_client_status

        def on_quit():
            shutdown_func()
            root.destroy()
            sys.exit(0)

        quit_btn = tk.Button(container, text="Stop Server & Quit", font=("Helvetica", 9, "bold"), bg="#F1F5F9", fg="#EF4444", activebackground="#FEE2E2", activeforeground="#DC2626", relief="flat", command=on_quit, cursor="hand2")
        quit_btn.pack(pady=(6, 12))

        root.protocol("WM_DELETE_WINDOW", on_quit)
        root.mainloop()
    except Exception as e:
        print(f"[!] GUI fallback to console: {e}")

def start_server(gui=True):
    ip = get_local_ip()
    server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    try:
        server_sock.bind(('0.0.0.0', PORT))
    except Exception as e:
        print(f"[!] Error binding to port {PORT}: {e}")
        sys.exit(1)

    server_sock.listen(5)
    start_discovery_beacon(ip, PORT)
    print_banner(ip, PORT)

    is_running = [True]

    def accept_loop():
        while is_running[0]:
            try:
                server_sock.settimeout(1.0)
                try:
                    conn, addr = server_sock.accept()
                except socket.timeout:
                    continue
                client_thread = threading.Thread(target=handle_client, args=(conn, addr), daemon=True)
                client_thread.start()
            except Exception as e:
                if is_running[0]:
                    print(f"[!] Socket error: {e}")
                break

    server_thread = threading.Thread(target=accept_loop, daemon=True)
    server_thread.start()

    def shutdown():
        is_running[0] = False
        try:
            server_sock.close()
        except Exception:
            pass

    use_gui = gui and ("--cli" not in sys.argv) and ("--headless" not in sys.argv)
    if use_gui:
        try:
            run_gui(ip, PORT, shutdown)
            return
        except Exception:
            pass

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nShutting down server.")
    finally:
        shutdown()

if __name__ == "__main__":
    start_server()
