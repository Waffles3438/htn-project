"""Optional browser regression check: requires Playwright and BROWSER_EXECUTABLE."""
import json
import os
import sys
import tempfile
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from playwright.sync_api import sync_playwright
from circuit.fixtures import fixture
from circuit.service import CircuitService
from server import create_server


def check():
    with tempfile.TemporaryDirectory() as directory, sync_playwright() as playwright:
        server = create_server(port=0, service=CircuitService(data_dir=directory))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        browser = playwright.chromium.launch(executable_path=os.environ.get("BROWSER_EXECUTABLE"))
        try:
            page = browser.new_page(viewport={"width": 1440, "height": 1100})
            errors = []
            page.on("pageerror", lambda error: errors.append(str(error)))
            page.goto("http://127.0.0.1:%s" % server.server_port)
            page.wait_for_selector("#demo-button", state="attached")
            page.wait_for_function("() => !document.getElementById('demo-button').disabled")
            # The redesigned page opens into a focused empty state; demo/parts controls live in Advanced options.
            page.locator("#advanced summary").click()
            for button in ("demo-button", "demo-led", "demo-arduino"):
                page.locator("#" + button).click()
                page.wait_for_selector("#download", state="attached")
                page.wait_for_function("() => !document.getElementById('download').disabled")
                assert page.locator("#preview svg").count() == 1
                assert "circuit checks passed" in page.locator("#status").inner_text()
                data = json.loads(page.locator("#json").text_content())
                assert data["breadboard"]["holeMapVersion"] == "person2-9d81633+rails1"
                assert page.locator("#connections button").count() > 0
                page.locator("#connections button").first.click()
                assert page.locator("#connections .selected").count() == 1
                page.locator("#clear-focus").click()
                assert page.locator("#connections .selected").count() == 0
            assert page.locator("#firmware").is_visible()
            with page.expect_download() as event:
                page.locator("#download").click()
            assert json.loads(Path(event.value.path()).read_text()) == data
            page.locator("#latest").click()
            page.wait_for_function("() => !document.getElementById('latest').disabled")
            assert json.loads(page.locator("#json").text_content()) == data
            page.locator("#demo-button").click()
            page.wait_for_function("() => !document.getElementById('download').disabled")
            assert page.locator("#firmware").is_hidden()
            page.screenshot(path="/tmp/circuit-desktop.png", full_page=True)
            page.locator("#board-view").select_option("full")
            assert page.locator("[data-hole]").count() == 830
            assert page.locator("[data-rail]").count() == 8
            geometry = page.evaluate("""() => {
                const h = id => document.querySelector(`[data-hole='${id}']`);
                const x = id => Number(h(id).getAttribute('cx'));
                const y = id => Number(h(id).getAttribute('cy'));
                return {left: x('A1')-x('L-A1'), right: x('R+A1')-x('J1'),
                    dx: x('B1')-x('A1'), dy: y('A2')-y('A1'),
                    radii: [...document.querySelectorAll('[data-hole]')].map(h=>h.getAttribute('r'))};
            }""")
            assert abs(geometry["left"] - geometry["right"]) < 1e-6
            assert abs(geometry["dx"] - geometry["dy"]) < 1e-6
            assert set(geometry["radii"]) == {"3"}
            assert page.locator("#preview").evaluate("e => e.scrollHeight > e.clientHeight")
            page.locator("#preview").evaluate("e => e.scrollTop = e.scrollHeight")
            page.screenshot(path="/tmp/circuit-full-board.png", full_page=True)
            page.locator("#preview").evaluate("e => e.scrollTop = 0")
            page.locator("#board-view").select_option("circuit")
            # Neither end of rail segment A is in this crop: stripes must still span visible holes.
            page.evaluate("""() => __circuit.show({components:[],jumperWires:[],externalConnections:[{
                id:'crop-test',pin:'D13',holeId:'J16',boardPosition:__circuit.board.holes.find(h=>h.id==='J16').position
            }]})""")
            assert page.locator("[data-rail]").count() == 4
            for stripe in page.locator("[data-rail]").all():
                assert float(stripe.get_attribute("y2")) - float(stripe.get_attribute("y1")) > 16
            page.evaluate("() => __circuit.restore()")
            # Failed requests retain the previous circuit rather than silently loading another one.
            previous = page.locator("#json").text_content()
            page.locator("#qty-0").fill("0")
            page.locator("#demo-button").click()
            page.wait_for_function("() => !document.getElementById('demo-button').disabled")
            assert page.locator("#status.error").count() == 1
            assert page.locator("#json").text_content() == previous
            page.locator("#qty-0").fill("1")
            # Existing session files are not migrated silently.
            old = json.loads(previous)
            old["breadboard"]["holeMapVersion"] = "person2-9d81633"
            page.route("**/api/sessions/*/placement", lambda route: route.fulfill(json=old))
            page.locator("#latest").click()
            page.wait_for_function("() => !document.getElementById('latest').disabled")
            assert "older board map" in page.locator("#status").inner_text()
            assert page.locator("#json").text_content() == previous
            page.unroute("**/api/sessions/*/placement")
            # Exercise the parts-only UI without a live provider call.
            plan, _ = fixture("button_led")
            page.route("**/api/circuits/analyze", lambda route: route.fulfill(json=plan))
            page.locator("#analyze").click()
            page.wait_for_function("() => !document.getElementById('analyze').disabled")
            assert page.locator("#source").inner_text() == "Parts identified"
            assert page.locator("#download").is_disabled()
            assert page.locator("#connections button").count() == 0
            page.locator("#demo-button").click()
            page.wait_for_function("() => !document.getElementById('download').disabled")
            page.set_viewport_size({"width": 390, "height": 844})
            assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
            assert page.locator("#preview").evaluate("e => e.scrollWidth > e.clientWidth")
            page.locator("#preview").evaluate("e => e.scrollLeft = e.scrollWidth")
            page.screenshot(path="/tmp/circuit-mobile.png", full_page=True)
            assert not errors, errors
            print("Browser checks passed: three circuits, geometry, crop, focus, export, session, failure, parts-only, mobile.")
        finally:
            browser.close()
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    check()
