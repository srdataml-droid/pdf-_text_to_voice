#!/usr/bin/env python3
"""Read selected GGUF header metadata without mapping or loading model tensors."""
import argparse
import json
from pathlib import Path
import struct

FORMATS = {0: "<B", 1: "<b", 2: "<H", 3: "<h", 4: "<I", 5: "<i", 6: "<f", 7: "<?", 10: "<Q", 11: "<q", 12: "<d"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", type=Path)
    parser.add_argument("--template-output", type=Path)
    args = parser.parse_args()
    fields = {}
    with args.file.open("rb") as source:
        def read(size):
            value = source.read(size)
            if len(value) != size:
                raise ValueError("Truncated GGUF header")
            return value

        def unpack(fmt):
            return struct.unpack(fmt, read(struct.calcsize(fmt)))[0]

        def string(keep=True):
            size = unpack("<Q")
            if size > 128 * 1024 * 1024:
                raise ValueError("Unreasonable header string size")
            if keep:
                return read(size).decode("utf-8")
            source.seek(size, 1)

        def value(kind, keep):
            if kind in FORMATS:
                result = unpack(FORMATS[kind])
                return result if keep else None
            if kind == 8:
                return string(keep)
            if kind == 9:
                item_kind, length = unpack("<I"), unpack("<Q")
                if length > 20_000_000:
                    raise ValueError("Unreasonable header array length")
                if item_kind in FORMATS and not keep:
                    source.seek(struct.calcsize(FORMATS[item_kind]) * length, 1)
                    return None
                if keep and length > 1024:
                    keep = False
                result = [] if keep else None
                for _ in range(length):
                    item = value(item_kind, keep)
                    if keep:
                        result.append(item)
                return result
            raise ValueError("Unsupported metadata type " + str(kind))

        if read(4) != b"GGUF":
            raise ValueError("Missing GGUF magic")
        version = unpack("<I")
        if version not in (2, 3):
            raise ValueError("Unsupported GGUF version")
        tensors, entries = unpack("<Q"), unpack("<Q")
        if entries > 1_000_000:
            raise ValueError("Unreasonable metadata count")
        for _ in range(entries):
            key, kind = string(), unpack("<I")
            keep = key in {"general.architecture", "general.name", "general.file_type", "tokenizer.chat_template",
                           "tokenizer.ggml.add_bos_token", "tokenizer.ggml.add_eos_token",
                           "tokenizer.ggml.bos_token_id", "tokenizer.ggml.eos_token_id"}
            result = value(kind, keep)
            if keep:
                fields[key] = result
        header_end = source.tell()
        if header_end > args.file.stat().st_size:
            raise ValueError("Truncated GGUF metadata")
    template = fields.pop("tokenizer.chat_template", None)
    if template is not None and args.template_output:
        args.template_output.write_text(template)
    print(json.dumps({"scope": "GGUF metadata inspection only; does not load, validate or execute tensor data.",
                      "file": str(args.file.resolve()), "file_bytes": args.file.stat().st_size,
                      "gguf_version": version, "tensor_count": tensors, "metadata_entries": entries,
                      "metadata_end_offset": header_end, "fields": fields,
                      "chat_template_present": template is not None,
                      "chat_template_uses_enable_thinking": bool(template and "enable_thinking" in template),
                      "chat_template_nonthinking_marker_present": bool(template and "<think></think>" in template)}, indent=2))


if __name__ == "__main__":
    main()
