#!/usr/bin/env python3
"""Writes the iOS app's strings from the Android app's, so both apps say the same words under the same keys.

    python3 tools/ios-strings.py

reads androidApp/src/main/res/values/strings.xml and values-iw/strings.xml and writes
iosApp/Talk/Resources/{en,he}.lproj/Localizable.strings (and Localizable.stringsdict for plurals). Run it after
changing strings.xml and commit what it writes. Android's formats become Foundation's: %1$s is %1$@, %1$d is %1$ld.
"""
import os
import re
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "androidApp", "src", "main", "res")
OUT = os.path.join(ROOT, "iosApp", "Talk", "Resources")
LANGUAGES = {"values": "en", "values-iw": "he"}


def text_of(element):
    """An Android string's text as the app shows it: the XML's escapes undone, <b> and the like dropped."""
    raw = "".join(element.itertext())
    raw = re.sub(r"\s*\n\s*", " ", raw).strip()
    if len(raw) >= 2 and raw[0] == '"' and raw[-1] == '"':
        raw = raw[1:-1]
    out = []
    i = 0
    while i < len(raw):
        c = raw[i]
        if c == "\\" and i + 1 < len(raw):
            n = raw[i + 1]
            if n == "n":
                out.append("\n")
            elif n == "t":
                out.append("\t")
            elif n == "u" and i + 5 < len(raw):
                out.append(chr(int(raw[i + 2:i + 6], 16)))
                i += 4
            else:
                out.append(n)
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def ios_format(text):
    """%1$s and %s take an object on iOS (%@), and a number is Swift's Int (%ld)."""
    text = re.sub(r"%(\d+\$)?s", lambda m: "%" + (m.group(1) or "") + "@", text)
    text = re.sub(r"%(\d+\$)?d", lambda m: "%" + (m.group(1) or "") + "ld", text)
    return text


def plural_format(text):
    """A plural's one number, as the stringsdict's rule names it."""
    return re.sub(r"%(\d+\$)?ld", "%ld", ios_format(text))


def quoted(text):
    return text.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\t", "\\t")


def xml_escaped(text):
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def read(folder):
    tree = ET.parse(os.path.join(RES, folder, "strings.xml"))
    strings = []
    plurals = []
    for element in tree.getroot():
        name = element.get("name")
        if element.tag == "string":
            strings.append((name, ios_format(text_of(element))))
        elif element.tag == "plurals":
            plurals.append((name, [(item.get("quantity"), plural_format(text_of(item))) for item in element]))
    return strings, plurals


def main():
    english, english_plurals = read("values")
    for folder, language in LANGUAGES.items():
        strings, plurals = read(folder)
        # A string not translated yet shows in English, as on Android (iOS would show its key).
        have = {name for name, _ in strings}
        strings += [(name, value) for name, value in english if name not in have]
        have = {name for name, _ in plurals}
        plurals += [(name, items) for name, items in english_plurals if name not in have]
        target = os.path.join(OUT, language + ".lproj")
        os.makedirs(target, exist_ok=True)
        with open(os.path.join(target, "Localizable.strings"), "w", encoding="utf-8") as f:
            f.write("/* Written by tools/ios-strings.py from the Android app's strings.xml. Don't edit: change strings.xml and run it. */\n")
            for name, value in strings:
                f.write('"%s" = "%s";\n' % (name, quoted(value)))
        with open(os.path.join(target, "Localizable.stringsdict"), "w", encoding="utf-8") as f:
            f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
            f.write('<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n')
            f.write('<plist version="1.0">\n<dict>\n')
            for name, items in plurals:
                f.write("\t<key>%s</key>\n\t<dict>\n" % name)
                f.write("\t\t<key>NSStringLocalizedFormatKey</key>\n\t\t<string>%#@count@</string>\n")
                f.write("\t\t<key>count</key>\n\t\t<dict>\n")
                f.write("\t\t\t<key>NSStringFormatSpecTypeKey</key>\n\t\t\t<string>NSStringPluralRuleType</string>\n")
                f.write("\t\t\t<key>NSStringFormatValueTypeKey</key>\n\t\t\t<string>ld</string>\n")
                for quantity, value in items:
                    f.write("\t\t\t<key>%s</key>\n\t\t\t<string>%s</string>\n" % (quantity, xml_escaped(value)))
                f.write("\t\t</dict>\n\t</dict>\n")
            f.write("</dict>\n</plist>\n")
        print("%s: %d strings, %d plurals" % (language, len(strings), len(plurals)))


if __name__ == "__main__":
    main()
