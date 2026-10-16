"""页面路由、PWA 资源，以及对前端“不得再出现 offline_mock”的守门。"""
import json
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
PAGES = ["/", "/goals", "/log", "/advisor", "/review", "/profile"]


@pytest.mark.parametrize("path", PAGES)
def test_page_route_returns_html_200(client, path):
    res = client.get(path)

    assert res.status_code == 200
    assert "text/html" in res.headers["content-type"]


def test_manifest_is_valid_standalone_pwa_manifest(client):
    res = client.get("/static/manifest.json")

    assert res.status_code == 200
    manifest = res.json()
    assert manifest["display"] == "standalone"
    assert manifest["theme_color"] == "#000000"
    assert manifest["icons"]


@pytest.mark.parametrize("path", ["/static/icon-192.png", "/static/icon-512.png", "/static/sw.js", "/static/js/app.js", "/static/css/style.css"])
def test_static_asset_is_served(client, path):
    assert client.get(path).status_code == 200


def test_manifest_icons_are_all_reachable(client):
    for icon in client.get("/static/manifest.json").json()["icons"]:
        src = icon["src"]
        assert client.get(src).status_code == 200, src


def test_service_worker_does_not_mention_offline_mock(client):
    assert "offline_mock" not in client.get("/static/sw.js").text


def _frontend_files():
    files = sorted((ROOT / "templates").glob("*.html"))
    files.append(ROOT / "static" / "js" / "app.js")
    files.append(ROOT / "static" / "sw.js")
    return files


@pytest.mark.parametrize("path", _frontend_files(), ids=lambda p: p.name)
def test_frontend_source_does_not_reference_offline_mock(path):
    assert "offline_mock" not in path.read_text(encoding="utf-8")


def test_manifest_file_is_valid_utf8_json():
    json.loads((ROOT / "static" / "manifest.json").read_text(encoding="utf-8"))


def test_production_python_code_has_no_offline_mock_path():
    offenders = [
        p.name for p in [ROOT / "ai_agent.py", ROOT / "routes" / "api_ai.py", ROOT / "routes" / "api_goals.py", ROOT / "services" / "goal_service.py"]
        if "offline_mock" in p.read_text(encoding="utf-8")
    ]
    assert offenders == []
