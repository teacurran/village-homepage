#!/usr/bin/env node
/**
 * install.cjs - Environment setup and dependency installation
 *
 * This script ensures that all project dependencies are installed and up-to-date.
 * It is idempotent and can be safely re-run without issues.
 *
 * For this Java/Maven/Quarkus project, it:
 * 1. Verifies Maven wrapper is executable
 * 2. Installs/updates Maven dependencies
 * 3. Installs/updates frontend dependencies (Node.js via frontend-maven-plugin)
 */

const { execSync, spawnSync } = require('child_process');
const fs = require('fs');
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
 * Main installation logic
 */
function main() {
    console.error('=== Village Homepage - Dependency Installation ===\n');

    // Verify Maven wrapper exists
    const mvnWrapperPath = path.join(process.cwd(), isWindows ? 'mvnw.cmd' : 'mvnw');
    if (!fs.existsSync(mvnWrapperPath)) {
        console.error('Error: Maven wrapper not found. Please ensure mvnw or mvnw.cmd exists in the project root.');
        process.exit(1);
    }

    // Make Maven wrapper executable on Unix-like systems
    if (!isWindows) {
        try {
            fs.chmodSync(mvnWrapperPath, '755');
        } catch (err) {
            console.error(`Warning: Could not make Maven wrapper executable: ${err.message}`);
        }
    }

    // Install/update Maven dependencies
    console.error('\n--- Installing Maven dependencies ---');
    executeCommand(mvnCmd, ['dependency:resolve', '-q']);

    // Install/update Maven plugin dependencies (including frontend-maven-plugin)
    console.error('\n--- Installing Maven plugin dependencies ---');
    executeCommand(mvnCmd, ['dependency:resolve-plugins', '-q']);

    // Install Node.js and NPM via frontend-maven-plugin
    // This runs the install-node-and-npm goal which downloads Node to target/node
    console.error('\n--- Installing Node.js and NPM (via frontend-maven-plugin) ---');
    executeCommand(mvnCmd, [
        'frontend:install-node-and-npm',
        '-q'
    ]);

    // Install NPM dependencies
    console.error('\n--- Installing NPM dependencies ---');
    executeCommand(mvnCmd, [
        'frontend:npm@npm-install',
        '-q'
    ]);

    console.error('\n=== Installation complete ===');
    process.exit(0);
}

// Run main function
if (require.main === module) {
    try {
        main();
    } catch (error) {
        console.error(`\nFatal error during installation: ${error.message}`);
        if (error.stack) {
            console.error(error.stack);
        }
        process.exit(1);
    }
}

module.exports = { executeCommand };
