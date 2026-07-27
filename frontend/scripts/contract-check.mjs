// Contract check: every server DTO named in the OpenAPI must come from the
// generated schema. Fails if any src file hand-declares one (interface/class/
// enum, or a `type X = { ... }` object literal). Re-export aliases such as
// `type X = components["schemas"]["X"]` are allowed.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

const CONTRACT = "../contracts/openapi/api.yaml";
const GENERATED = join("src", "api", "schema.d.ts");

const yaml = readFileSync(CONTRACT, "utf8");
const schemasBlock = yaml.split(/\n\s*schemas:\s*\n/)[1] ?? "";
const names = [...schemasBlock.matchAll(/^ {4}(\w+):/gm)].map((m) => m[1]);
if (names.length === 0) {
  console.error(`No component schemas found in ${CONTRACT}.`);
  process.exit(1);
}

function walk(dir) {
  return readdirSync(dir).flatMap((entry) => {
    const path = join(dir, entry);
    return statSync(path).isDirectory() ? walk(path) : [path];
  });
}

const files = walk("src").filter(
  (f) => /\.(ts|tsx)$/.test(f) && f !== GENERATED,
);

const dupes = [];
for (const name of names) {
  const re = new RegExp(
    `\\b(?:interface|class|enum)\\s+${name}\\b|\\btype\\s+${name}\\s*=\\s*\\{`,
  );
  for (const file of files) {
    if (re.test(readFileSync(file, "utf8"))) dupes.push(`${name} → ${file}`);
  }
}

if (dupes.length > 0) {
  console.error(
    "Hand-written server DTOs found. Import from src/api/schema instead:",
  );
  for (const d of dupes) console.error("  " + d);
  process.exit(1);
}

console.log(
  `Contract check passed: ${names.length} server DTOs are generated-only.`,
);
