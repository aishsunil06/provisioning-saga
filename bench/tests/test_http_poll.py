"""The poller must survive the system being unavailable.

Unavailability is the condition the kill profile exists to create, so a poller
that treats a 500 or a refused connection as fatal cannot measure the very run
it was written for. This is not defensive programming for its own sake: it
aborted a real run of arm B under the kill profile before this test existed.
"""

import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from bench.driver import http_driver


class _Responder(BaseHTTPRequestHandler):
    codes: list[int] = []
    body = b'{"state":"ACTIVE"}'

    def do_GET(self):  # noqa: N802
        code = self.codes.pop(0) if self.codes else 200
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        if code == 200:
            self.wfile.write(self.body)

    def log_message(self, *args):
        pass


@pytest.fixture
def server():
    httpd = ThreadingHTTPServer(("localhost", 0), _Responder)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    yield f"http://localhost:{httpd.server_port}"
    httpd.shutdown()


def test_a_server_error_is_not_terminal_and_not_fatal(server):
    _Responder.codes = [500, 503, 200]
    driver = http_driver(server)

    # Reaching into the poller directly: what is under test is how it classifies
    # responses, not the pacing that Driver.run adds around it.
    assert driver._poll("any-id") == "UNKNOWN"
    assert driver._poll("any-id") == "UNKNOWN"
    assert driver._poll("any-id") == "ACTIVE"


def test_a_missing_activation_reads_as_pending(server):
    _Responder.codes = [404]

    assert http_driver(server)._poll("any-id") == "PENDING"


def test_a_refused_connection_is_not_fatal():
    # Nothing is listening on this port.
    assert http_driver("http://localhost:1")._poll("any-id") == "UNKNOWN"
