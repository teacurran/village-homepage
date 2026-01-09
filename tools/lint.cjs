#!/usr/bin/env node
/**
 * lint.cjs - Lint Java source code using Spotless
 *
 * This script:
 * 1. Ensures dependencies are installed (silently)
 * 2. Runs Spotless formatter in check mode
 * 3. Outputs results as JSON array to stdout
 */

const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

// Determine platform-specific Maven wrapper command
const isWindows = process.platform === 'win32';
const mvnCmd = isWindows ? 'mvnw.cmd' : './mvnw';

/**
 * Execute a command silently and return result
 */
function executeCommandSilent(command, args = []) {
    const result = spawnSync(command, args, {
        stdio: ['ignore', 'pipe', 'pipe'],
        shell: isWindows,
        cwd: process.cwd(),
        encoding: 'utf-8'
    });

    return {
        success: result.status === 0,
        status: result.status,
        stdout: result.stdout || '',
        stderr: result.stderr || '',
        error: result.error
    };
}

/**
 * Execute a command with visible stderr
 */
function executeCommand(command, args = []) {
    const result = spawnSync(command, args, {
        stdio: ['ignore', 'pipe', 'inherit'],
        shell: isWindows,
        cwd: process.cwd(),
        encoding: 'utf-8'
    });

    return {
        success: result.status === 0,
        status: result.status,
        stdout: result.stdout || '',
        stderr: result.stderr || '',
        error: result.error
    };
}

/**
 * Parse Spotless output and convert to JSON format
 */
function parseSpotlessOutput(output) {
    const errors = [];

    // Spotless outputs violations like:
    // [ERROR] src/main/java/path/to/File.java
    // or multiple violations in the summary

    const lines = output.split('\n');
    const filePattern = /^\[ERROR\]\s+(.+\.java)/;
    const summaryPattern = /The following files had format violations:/i;

    let inViolationsList = false;

    for (const line of lines) {
        const trimmed = line.trim();

        if (summaryPattern.test(trimmed)) {
            inViolationsList = true;
            continue;
        }

        const match = trimmed.match(filePattern);
        if (match) {
            const filePath = match[1];
            errors.push({
                type: 'formatting',
                path: filePath,
                obj: '',
                message: 'Code formatting violation detected. Run ./mvnw spotless:apply to fix.',
                line: '',
                column: ''
            });
        } else if (inViolationsList && trimmed.length > 0 && !trimmed.startsWith('[')) {
            // Additional file listed in summary
            if (trimmed.endsWith('.java')) {
                errors.push({
                    type: 'formatting',
                    path: trimmed,
                    obj: '',
                    message: 'Code formatting violation detected. Run ./mvnw spotless:apply to fix.',
                    line: '',
                    column: ''
                });
            }
        }
    }

    return errors;
}

/**
 * Main linting logic
 */
function main() {
    console.error('=== Village Homepage - Lint Check ===\n');

    // Step 1: Ensure dependencies are installed (silently)
    console.error('--- Checking dependencies (silent) ---');
    const installResult = executeCommandSilent('node', ['tools/install.cjs']);
    if (!installResult.success) {
        console.error('Dependency installation failed');
        console.log(JSON.stringify([]));
        process.exit(1);
    }

    // Step 2: Run Spotless check
    console.error('--- Running Spotless format check ---');
    const lintResult = executeCommand(mvnCmd, [
        'spotless:check',
        '-q'
    ]);

    const errors = [];

    if (!lintResult.success) {
        // Parse the output to find formatting violations
        const combinedOutput = lintResult.stdout + '\n' + lintResult.stderr;
        const parsedErrors = parseSpotlessOutput(combinedOutput);
        errors.push(...parsedErrors);

        // If we couldn't parse specific files, add a generic error
        if (parsedErrors.length === 0) {
            errors.push({
                type: 'formatting',
                path: '',
                obj: '',
                message: 'Spotless formatting check failed. Run ./mvnw spotless:apply to fix formatting issues.',
                line: '',
                column: ''
            });
        }
    }

    // Step 3: Output results as JSON
    console.log(JSON.stringify(errors, null, 2));

    // Exit with appropriate code
    if (errors.length > 0) {
        console.error(`\n--- Found ${errors.length} formatting issue(s) ---`);
        process.exit(1);
    } else {
        console.error('\n--- No formatting issues found ---');
        process.exit(0);
    }
}

// Run main function
if (require.main === module) {
    try {
        main();
    } catch (error) {
        console.error(`\nFatal error during linting: ${error.message}`);
        // Output empty JSON array on fatal error
        console.log(JSON.stringify([]));
        process.exit(1);
    }
}
