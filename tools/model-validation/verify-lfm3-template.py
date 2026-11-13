#!/usr/bin/env python3
"""Compare the app's actual narrow LFM3 renderer with the pinned vendor Jinja.

This is a host formatting test, not an Android load/performance test. Requires
a host C++ compiler and Jinja2; does not execute model code or download weights.
"""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

from jinja2 import Environment, nodes
from jinja2.ext import Extension


class Generation(Extension):
    # The HF generation tag marks answer spans; it does not change the text.
    tags = {"generation"}

    def parse(self, parser):
        token = next(parser.stream)
        body = parser.parse_statements(["name:endgeneration"], drop_needle=True)
        return nodes.CallBlock(self.call_method("render"), [], [], body).set_lineno(token.lineno)

    def render(self, caller):
        return caller()


def literal(value):
    # UTF-8 bytes in C++ string literals avoid locale-dependent source decoding.
    return '"' + ''.join('\\x%02x' % b for b in value.encode("utf-8")) + '"'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="anchor_llama.cpp to test")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    source = args.source.read_text()
    begin = "// BEGIN LFM3 PLAIN TEXT FORMATTER"
    end = "// END LFM3 PLAIN TEXT FORMATTER"
    if begin not in source or end not in source:
        raise SystemExit("FAIL: explicit LFM3 formatter is absent")
    helper = source.split(begin, 1)[1].split(end, 1)[0]
    cases = [
        [("system", "Be concise."), ("user", "Hello.")],
        [("user", "Hello.")],
        [("system", ""), ("user", "Hello.")],
        [("system", "Be concise."), ("user", "Remember this."), ("assistant", "Saved."), ("user", "What did I say?")],
        [("user", "مرحبا 🌍 — Ẹ káàárọ̀"), ("assistant", "Bonjour 🌞"), ("user", "Repeat the greeting.")],
        [("user", "First"), ("assistant", "<think>private thought</think>  Visible answer. \n"), ("user", "Next")],
        [("user", "First"), ("assistant", "<think>x</think>y</think>\u00a0Visible\u3000"), ("user", "Next")],
        [("system", "\n Policy \n"), ("user", "\n Preserve this whitespace. \n")],
    ]
    invalid = [[("tool", "result"), ("user", "Hi")], [("assistant", "unfinished")],
               [("user", "Hi"), ("system", "later"), ("user", "next")], []]
    code = '#include <string>\n#include <vector>\n#include <stdexcept>\n#include <iostream>\nstruct Message { std::string role, content; };\n' + helper
    code += '\nint main() {\n'
    for messages in cases:
        value = '{' + ','.join('{' + literal(role) + ',' + literal(content) + '}' for role, content in messages) + '}'
        code += '{ auto result = formatLfm3PlainText(' + value + ', "<|startoftext|>"); std::cout << result.size() << "\\n"; std::cout.write(result.data(), result.size()); std::cout << "\\n"; }\n'
    for messages in invalid:
        value = '{' + ','.join('{' + literal(role) + ',' + literal(content) + '}' for role, content in messages) + '}'
        code += 'try { formatLfm3PlainText(' + value + ', "<|startoftext|>"); return 9; } catch(const std::runtime_error &) {}\n'
    code += '}\n'
    with tempfile.TemporaryDirectory(prefix="anchor-lfm3-format-") as folder:
        cpp = Path(folder) / "probe.cpp"
        exe = Path(folder) / "probe"
        cpp.write_text(code)
        subprocess.run(["c++", "-std=c++17", "-Wall", "-Wextra", "-Werror", str(cpp), "-o", str(exe)], check=True)
        rendered = subprocess.check_output([str(exe)])
    environment = Environment(extensions=[Generation])
    vendor = environment.from_string((root / "evidence/LFM3-1.7B-chat_template.jinja").read_text())
    observations = []
    for index, messages in enumerate(cases):
        length, rendered = rendered.split(b"\n", 1)
        actual = rendered[:int(length)]
        rendered = rendered[int(length) + 1:]
        expected = vendor.render(messages=[{"role": r, "content": c} for r, c in messages],
                                 bos_token="<|startoftext|>", tools=None, add_generation_prompt=True,
                                 enable_thinking=False).encode("utf-8")
        match = actual == expected
        observations.append({"case": index + 1, "matches_vendor_plain_text_nonthinking": match,
                             "actual_utf8": actual.decode(), "expected_utf8": expected.decode()})
        if not match:
            print(json.dumps(observations[-1], ensure_ascii=False, indent=2))
            raise SystemExit("FAIL: template text differs")
    print(json.dumps({"scope": "Host formatting parity only, including explicit BOS required by official LFM3 GGUF metadata.",
                      "source": str(args.source.resolve()), "valid_cases_passed": len(cases),
                      "invalid_cases_rejected": len(invalid), "cases": observations}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
