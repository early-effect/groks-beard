#!/usr/bin/env node
import { runMcpProxyMain } from "./proxy-main.js"

void runMcpProxyMain(process.argv.slice(2))
