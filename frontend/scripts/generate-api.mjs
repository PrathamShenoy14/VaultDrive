import openapiTS, { astToString } from "openapi-typescript";
import ts from "typescript";
import { readFile, writeFile } from "node:fs/promises";
const target = new URL("../src/lib/api/generated.ts", import.meta.url);
const ast = await openapiTS(
  new URL("../../docs/openapi.json", import.meta.url),
  {
    transform(schema) {
      // JSON int64 values are parsed losslessly; never silently round quota/revisions.
      if (schema.type === "integer" && schema.format === "int64")
        return ts.factory.createUnionTypeNode([
          ts.factory.createKeywordTypeNode(ts.SyntaxKind.NumberKeyword),
          ts.factory.createKeywordTypeNode(ts.SyntaxKind.BigIntKeyword),
        ]);
    },
  },
);
const output = astToString(ast);
if (process.argv.includes("--check")) {
  if ((await readFile(target, "utf8")) !== output)
    throw new Error("API types differ. Run npm run api:generate.");
} else await writeFile(target, output);
