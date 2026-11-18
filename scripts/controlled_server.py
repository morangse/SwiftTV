"""Original generated test-pattern HLS for playback acceptance; no broadcast content."""
import argparse
import json
import math
import os
import pathlib
import subprocess
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

parser = argparse.ArgumentParser()
parser.add_argument("--ffmpeg", required=True)
parser.add_argument("--port", type=int, default=8765)
parser.add_argument("--bind", default="127.0.0.1")
parser.add_argument("--advertise", default="127.0.0.1")
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[1] / "output" / "controlled"
root.mkdir(parents=True, exist_ok=True)
manifest = root / "source.m3u8"
if not manifest.exists():
    command = [args.ffmpeg, "-nostdin", "-hide_banner", "-y",
               "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=25",
               "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
               "-t", "30", "-c:v", "libx264", "-preset", "veryfast", "-b:v", "1500k",
               "-pix_fmt", "yuv420p", "-g", "25", "-sc_threshold", "0",
               "-c:a", "aac", "-b:a", "96k", "-f", "hls", "-hls_time", "1",
               "-hls_playlist_type", "vod", "-hls_segment_filename", str(root / "segment%03d.ts"),
               str(manifest)]
    subprocess.run(command, check=True, stdout=subprocess.DEVNULL,
                   stderr=(root / "generation.log").open("w"),
                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
start = time.monotonic()
fault = False
base = "http://" + args.advertise + ":" + str(args.port)

class Handler(BaseHTTPRequestHandler):
    def send(self, status, content, mime="text/plain; charset=utf-8"):
        if isinstance(content, str):
            content = content.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(content)

    def do_GET(self):
        route = urlparse(self.path).path
        if route == "/catalog.m3u":
            text = "#EXTM3U\n"
            for channel in ("A", "B"):
                text += '#EXTINF:-1 tvg-id="controlled-' + channel + '" group-title="Controlled",测试频道 ' + channel + "\n"
                text += base + "/live/" + channel + ".m3u8\n"
            self.send(200, text, "application/x-mpegURL")
        elif route == "/failover.m3u":
            text = '#EXTM3U\n#EXTINF:-1 tvg-id="failover" group-title="Controlled",故障切源测试\n'
            text += base + "/dead/live.m3u8\n"
            text += '#EXTINF:-1 tvg-id="failover" group-title="Controlled",故障切源测试\n'
            text += base + "/live/A.m3u8\n"
            self.send(200, text, "application/x-mpegURL")
        elif route == "/live/expired":
            self.send(403, "Expired")
        elif route.startswith("/live/segment") and route.endswith(".ts"):
            name = pathlib.PurePosixPath(route).name
            target = root / name
            if target.name.startswith("segment") and target.is_file():
                self.send(200, target.read_bytes(), "video/mp2t")
            else:
                self.send(404, "Missing segment")
        elif route in ("/live/A.m3u8", "/live/B.m3u8", "/live/no-extension"):
            if fault:
                self.send(503, "Simulated source outage")
                return
            sequence = max(0, math.floor(time.monotonic() - start) - 6)
            text = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:" + str(sequence) + "\n#EXT-X-DISCONTINUITY-SEQUENCE:" + str(max(0, sequence - 1) // 30) + "\n"
            for index in range(sequence, sequence + 6):
                if index > 0 and index % 30 == 0:
                    text += "#EXT-X-DISCONTINUITY\n"
                text += "#EXTINF:1.000000,\nsegment" + str(index % 30).zfill(3) + ".ts\n"
            self.send(200, text, "application/x-mpegURL")
        elif route == "/status":
            self.send(200, json.dumps({"fault": fault}), "application/json")
        else:
            self.send(404, "Missing")

    def do_POST(self):
        global fault
        if self.path != "/control" or self.client_address[0] not in ("127.0.0.1", "::1"):
            self.send(403, "Loopback control only")
            return
        length = int(self.headers.get("Content-Length", "0"))
        if not 0 < length < 1024:
            self.send(400, "Invalid length")
            return
        fault = bool(json.loads(self.rfile.read(length))["fault"])
        self.send(200, json.dumps({"fault": fault}), "application/json")

    def log_message(self, fmt, *values):
        print(time.strftime("%H:%M:%S"), fmt % values, flush=True)

print("CONTROLLED_PLAYLIST=" + base + "/catalog.m3u", flush=True)
ThreadingHTTPServer((args.bind, args.port), Handler).serve_forever()
