import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

test("the deployment egress inventory is an exact default-deny HTTPS host allowlist", () => {
  const testsDirectory = dirname(fileURLToPath(import.meta.url));
  const inventory = JSON.parse(readFileSync(
    join(testsDirectory, "..", "deploy", "egress-hosts.json"),
    "utf8",
  )) as {
    schemaVersion: number;
    defaultAction: string;
    transport: string;
    port: number;
    hosts: Array<{ hostname: string }>;
  };
  assert.equal(inventory.schemaVersion, 1);
  assert.equal(inventory.defaultAction, "deny");
  assert.equal(inventory.transport, "tcp");
  assert.equal(inventory.port, 443);
  assert.deepEqual(
    inventory.hosts.map(({ hostname }) => hostname).sort(),
    [
      "oauth2.googleapis.com",
      "restapi.amap.com",
      "routes.googleapis.com",
      "www.googleapis.com",
    ],
  );
});

test("reverse proxy and backup templates preserve HTTPS and the selected Compose project", () => {
  const testsDirectory = dirname(fileURLToPath(import.meta.url));
  const deployDirectory = join(testsDirectory, "..", "deploy");
  const nginx = readFileSync(join(deployDirectory, "nginx-api.conf"), "utf8");
  assert.match(nginx, /listen 443 ssl;/);
  assert.match(nginx, /return 308 https:\/\/\$host\$request_uri;/);
  assert.match(nginx, /proxy_set_header X-Forwarded-Proto https;/);
  assert.doesNotMatch(nginx, /proxy_pass http:\/\/127\.0\.0\.1:8788/);

  const backup = readFileSync(join(deployDirectory, "backup.sh"), "utf8");
  assert.match(backup, /active-compose-project/);
  assert.match(backup, /-p "\$active_project" exec -T api node dist\/admin\.js backup/);
  assert.doesNotMatch(backup, /docker compose exec -T api/);
});
