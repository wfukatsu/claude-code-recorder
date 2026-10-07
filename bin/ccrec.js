#!/usr/bin/env node
import { main } from '../src/cli.js';

main(process.argv.slice(2)).then(
  (code) => process.exit(code),
  (error) => {
    console.error(`ccrec: ${error.message}`);
    process.exit(1);
  },
);
