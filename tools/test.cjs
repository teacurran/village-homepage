#!/usr/bin/env node
/**
 * test.cjs - Run project tests
 *
 * This script:
 * 1. Ensures dependencies are installed via install.cjs
 * 2. Runs Maven tests (Surefire for unit tests, optionally Failsafe for integration tests)
 */

const { spawnSync } = require('child_process');
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
 * Main test execution logic
 */
function main() {
    console.error('=== Village Homepage - Run Tests ===\n');

    // Step 1: Ensure dependencies are installed
    console.error('--- Checking dependencies ---');
    executeCommand('node', ['tools/install.cjs']);

    // Step 2: Run tests
    console.error('\n--- Running tests ---');

    // Check if we should run integration tests
    // By default, pom.xml has skipITs=true, so we only run unit tests
    // To run integration tests, users can set -DskipITs=false
    const runIntegrationTests = process.argv.includes('--integration') ||
                                process.argv.includes('--it');

    if (runIntegrationTests) {
        console.error('Running unit and integration tests...\n');
        executeCommand(mvnCmd, ['verify', '-DskipITs=false']);
    } else {
        console.error('Running unit tests only (use --integration to include integration tests)...\n');
        executeCommand(mvnCmd, ['test']);
    }

    console.error('\n=== Tests completed successfully ===');
    process.exit(0);
}

// Run main function
if (require.main === module) {
    try {
        main();
    } catch (error) {
        console.error(`\nFatal error during test execution: ${error.message}`);
        if (error.stack) {
            console.error(error.stack);
        }
        process.exit(1);
    }
}
