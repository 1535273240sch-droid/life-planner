import os
import sys
import time
import socket
import threading
import webbrowser
import uvicorn

# 确保控制台支持 UTF-8 打印
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

def get_lan_ip() -> str:
    """获取本机在局域网中的真实 IP 地址，供手机直连访问"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        try:
            return socket.gethostbyname(socket.gethostname())
        except Exception:
            return "127.0.0.1"

def open_browser(url: str, delay: float = 1.2):
    """延迟并在后台自动打开本地浏览器"""
    def _open():
        time.sleep(delay)
        try:
            webbrowser.open(url)
        except Exception:
            pass
    threading.Thread(target=_open, daemon=True).start()

def main():
    from config import settings

    lan_ip = get_lan_ip()
    port = settings.APP_PORT
    local_url = f"http://127.0.0.1:{port}"
    mobile_url = f"http://{lan_ip}:{port}"

    border = "=" * 68
    print("\n" + border)
    print("  🚀 人生智能编排体 (Life Planner AI) - 移动端全栈系统启动中")
    print(border)
    print(f"  💻 电脑本地访问:   {local_url}")
    print(f"  📱 手机局域网访问: {mobile_url}  👈 (手机连同一Wi-Fi直接打开)")
    print("-" * 68)
    print("  【📱 手机端秒变独立全屏原生 App 指南】")
    print("  • 苹果 iOS (Safari):  打开手机地址 -> 底部分享按钮 ->「添加到主屏幕」")
    print("  • 安卓 (Chrome/微信): 打开手机地址 -> 菜单「添加到主屏幕」或「安装应用」")
    print("  • 体验特点: 独立图标、无浏览器地址栏、全屏原生 OLED 级质感、离线响应")
    print(border + "\n")

    # 自动打开电脑浏览器访问
    open_browser(local_url, delay=1.5)

    # 绑定 0.0.0.0 允许手机及同局域网设备访问
    uvicorn.run("main:app", host="0.0.0.0", port=port, reload=False)

if __name__ == "__main__":
    main()
