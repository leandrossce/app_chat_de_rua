"""Check complete Android translations and compatible formatting placeholders."""
from collections import Counter
from pathlib import Path
import re
import xml.etree.ElementTree as ET

RES = Path(__file__).resolve().parents[1] / "app/src/main/res"
LOCALES = ("en-rUS", "de-rDE", "zh-rCN", "es-rES")
TOKEN = re.compile(r"%(?:\d+\$)?[dsf]|%%")


def resources(path):
    root = ET.parse(path).getroot()
    entries = {(item.tag, item.attrib["name"]): item for item in root}
    assert len(entries) == len(root), f"Duplicate resources: {path}"
    return entries


def placeholders(value):
    return Counter(TOKEN.findall("".join(value.itertext())))


base = resources(RES / "values/strings.xml")
for locale in LOCALES:
    translated = resources(RES / f"values-{locale}/strings.xml")
    assert base.keys() == translated.keys(), (
        locale, "missing", base.keys() - translated.keys(),
        "extra", translated.keys() - base.keys())
    for key, original in base.items():
        translation = translated[key]
        assert "".join(translation.itertext()).strip(), (locale, key, "empty")
        if original.tag == "plurals":
            variants = {item.attrib["quantity"]: item for item in translation}
            assert "other" in variants, (locale, key, "missing other")
            for variant in variants.values():
                assert placeholders(variant) == placeholders(original.find("item")), (locale, key)
        else:
            assert placeholders(original) == placeholders(translation), (locale, key)
    print(f"OK {locale}: {len(translated)} resources, plurals and placeholders")
print("OK pt-BR: complete default resources")
