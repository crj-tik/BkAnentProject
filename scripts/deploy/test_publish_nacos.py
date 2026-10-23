import contextlib
import io
import importlib.util
import json
import os
import tempfile
import unittest
import urllib.parse
import urllib.request
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "publish_nacos", Path(__file__).with_name("publish-nacos.py"))
publish_nacos = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publish_nacos)


class FakeResponse:
    def __init__(self, body):
        self.body = body

    def read(self):
        return self.body

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False


class FakeOpener:
    def __init__(self, content):
        self.content = content.encode()
        self.calls = []

    def open(self, request, timeout):
        url = urllib.parse.urlsplit(request.full_url)
        values = urllib.parse.parse_qs((request.data or url.query.encode()).decode())
        self.calls.append((url.path, request.method, values, timeout))
        if url.path.endswith("/nacos/v3/auth/user/login"):
            return FakeResponse(json.dumps({"data": {"accessToken": "fixture-token"}}).encode())
        if request.method == "POST":
            self.content = values["content"][0].encode()
            return FakeResponse(b"true")
        return FakeResponse(self.content)


class PublishNacosTest(unittest.TestCase):
    def run_publish(self, auth_enabled, opener, include_credentials=False, data_id="memory-service.yaml"):
        with tempfile.TemporaryDirectory() as temp_dir:
            service_name = data_id.removesuffix(".yaml")
            content = f"spring:\n  application:\n    name: {service_name}\n"
            Path(temp_dir, data_id).write_text(content, encoding="utf-8")
            environment = {
                "NACOS_NAMESPACE": "BK-AGENT",
                "NACOS_CONFIG_GROUP": "DEFAULT_GROUP",
                "NACOS_CONFIG_DATA_IDS": data_id,
                "NACOS_AUTH_ENABLE": auth_enabled,
                "NACOS_TEMPLATE_DIR": temp_dir,
            }
            if include_credentials:
                environment.update({"NACOS_USERNAME": "fixture-user", "NACOS_PASSWORD": "fixture-password"})
            output = io.StringIO()
            with patch.dict(os.environ, environment, clear=True), \
                    patch.object(publish_nacos.urllib.request, "build_opener", return_value=opener), \
                    contextlib.redirect_stdout(output):
                publish_nacos.main()
            return output.getvalue()

    def test_auth_disabled_publishes_only_allowlisted_config_without_login(self):
        content = "spring:\n  application:\n    name: memory-service\n"
        opener = FakeOpener(content)

        output = self.run_publish("false", opener)

        self.assertFalse(any(path.endswith("/auth/user/login") for path, *_ in opener.calls))
        self.assertEqual(2, len(opener.calls))
        self.assertTrue(all(path == "/nacos/v1/cs/configs" for path, *_ in opener.calls))
        self.assertTrue(all("accessToken" not in values for _, _, values, _ in opener.calls))
        self.assertNotIn("fixture-token", output)

    def test_runtime_entry_configs_are_explicitly_allowlisted(self):
        for data_id in ("interview-service.yaml", "gateway.yaml"):
            with self.subTest(data_id=data_id):
                content = f"spring:\n  application:\n    name: {data_id.removesuffix('.yaml')}\n"
                opener = FakeOpener(content)

                output = self.run_publish("false", opener, data_id=data_id)

                config_calls = [call for call in opener.calls if call[0] == "/nacos/v1/cs/configs"]
                self.assertEqual(2, len(config_calls))
                self.assertTrue(all(values.get("dataId") == [data_id] for _, _, values, _ in config_calls))
                self.assertNotIn("fixture-token", output)

    def test_auth_enabled_logs_in_and_sends_token_without_printing_it(self):
        content = "spring:\n  application:\n    name: memory-service\n"
        opener = FakeOpener(content)

        output = self.run_publish("true", opener, include_credentials=True)

        self.assertEqual(1, sum(path.endswith("/auth/user/login") for path, *_ in opener.calls))
        config_calls = [call for call in opener.calls if call[0] == "/nacos/v1/cs/configs"]
        self.assertEqual(2, len(config_calls))
        self.assertTrue(all(values.get("accessToken") == ["fixture-token"] for _, _, values, _ in config_calls))
        self.assertNotIn("fixture-token", output)
        self.assertNotIn("fixture-password", output)

    def test_auth_mode_must_be_explicit(self):
        with self.assertRaisesRegex(ValueError, "Explicit Nacos authentication mode"):
            self.run_publish("", FakeOpener(""))


if __name__ == "__main__":
    unittest.main()
