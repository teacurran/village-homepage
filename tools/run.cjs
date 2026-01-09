#!/usr/bin/env node
/**
 * run.cjs - Run the Village Homepage application
 *
 * This script:
 * 1. Ensures dependencies are installed via install.cjs
 * 2. Starts the Quarkus development server
 */

const { execSync, spawnSync } = require('child_process');
const path = require('path');

// Determine platform-specific Maven wrapper command
const isWindows = process.platform === 'win32';
const mvnCmd = isWindows ? 'mvnw.cmd' : './mvnw';

/**
 * Execute a command and handle errors appropriately
 */
function executeCommand(command, args = [], options = {}) {
    console.error(`Executing: ${command} ${args.join(' ')}`);

    const result = spawnSync(command, args, {
        stdio: 'inherit',
        shell: isWindows,
        cwd: process.cwd(),
        ...options
    });

    if (result.error) {
        console.error(`Error executing command: ${result.error.message}`);
        process.exit(1);
    }

    if (result.status !== 0) {
        console.error(`Command failed with exit code ${result.status}`);
        process.exit(result.status || 1);
    }

    return result;
}

/**
 * Main execution logic
 */
function main() {
    console.error('=== Village Homepage - Run Application ===\n');

    // Step 1: Ensure dependencies are installed
    console.error('--- Checking dependencies ---');
    executeCommand('node', ['tools/install.cjs']);

    // Step 2: Start Quarkus in development mode
    console.error('\n--- Starting Quarkus development server ---');
    console.error('The application will be available at http://localhost:8080');
    console.error('Press Ctrl+C to stop\n');

    executeCommand(mvnCmd, ['quarkus:dev']);

    process.exit(0);
}

// Run main function
if (require.main === module) {
    try {
        main();
    } catch (error) {
        console.error(`\nFatal error during execution: ${error.message}`);
        if (error.stack) {
            console.error(error.stack);
        }
        process.exit(1);
    }
}
