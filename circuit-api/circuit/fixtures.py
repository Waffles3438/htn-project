"""Explicit offline lessons, never substituted for a failed live generation."""
from .board import MODEL, default_inventory
from .validation import CircuitError

PROMPTS = {"button_led": "Turn on an LED when a button is pressed", "led": "Turn on a red LED", "arduino_led": "Blink an external red LED every second using an Arduino Uno R3"}


def request_for(name="button_led"):
    inventory = default_inventory()
    if name == "arduino_led":
        inventory.append({"type": "arduino_uno", "value": "Uno R3", "quantity": 1})
    return {"prompt": PROMPTS[name], "availableParts": inventory,
            "breadboardModel": MODEL, "sessionId": "demo-button-led"}


def fixture(name):
    if name not in PROMPTS:
        raise CircuitError("UNKNOWN_FIXTURE", "Choose led or button_led.", 404)
    if name == "arduino_led":
        plan, draft = fixture("led")
        plan.update(title="Arduino Uno · blinking LED", behavior="blink")
        plan["components"][0].update(type="arduino_uno", value="Uno R3", purpose="Drive LED from digital pin 13")
        return plan, draft
    button = name == "button_led"
    plan = {"title": "Button-controlled LED" if button else "Simple LED",
            "behavior": "while_pressed" if button else "always_on",
            "explanation": "The 220 ohm resistor limits LED current in the series circuit.",
            "components": [
                {"id": "power_1", "type": "power_supply", "value": "5V", "purpose": "Power the circuit"},
                {"id": "led_1", "type": "led", "value": "red", "purpose": "Emit light"},
                {"id": "resistor_1", "type": "resistor", "value": "220ohm", "purpose": "Limit LED current"}]}
    components = [
        {"id": "led_1", "terminals": [{"name": "anode", "hole": "A15"}, {"name": "cathode", "hole": "A16"}], "buildStep": 1},
        {"id": "resistor_1", "terminals": [{"name": "a", "hole": "B12"}, {"name": "b", "hole": "B15"}], "buildStep": 2}]
    if button:
        plan["components"].append({"id": "button_1", "type": "button", "value": "momentary", "purpose": "Close circuit only while pressed"})
        components.append({"id": "button_1", "terminals": [{"name": n, "hole": h} for n,h in
                           [("a1", "E8"), ("a2", "E11"), ("b1", "F8"), ("b2", "F11")]], "buildStep": 3})
    wires = [{"id": "wire_1", "from": "I1", "to": "D8" if button else "C12", "color": "red", "buildStep": len(components)+1}]
    if button:
        wires.append({"id": "wire_2", "from": "G8", "to": "C12", "color": "yellow", "buildStep": 5})
    wires.append({"id": "wire_3", "from": "C16", "to": "I2", "color": "black", "buildStep": len(components)+len(wires)+1})
    components.append({"id": "power_1", "terminals": [{"name": "positive", "hole": "J1"}, {"name": "negative", "hole": "J2"}],
                       "buildStep": len(components)+len(wires)+1})
    return plan, {"components": components, "wires": wires}
