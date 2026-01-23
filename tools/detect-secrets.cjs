#!/usr/bin/env node
/**
 * detect-secrets.cjs - Secret Leak Detection Tool
 *
 * Scans codebase for accidentally committed secrets and sensitive credentials.
 * Prevents security breaches by catching secrets before they reach version control.
 *
 * Security Baseline: I1.T9
 * Related Policies: P1, P3, P5, P9, P14
 *
 * Usage:
 *   npm run lint:secrets                    # Scan all tracked files
 *   node tools/detect-secrets.cjs <file>    # Scan specific file
 *
 * Exit Codes:
 *   0 - No secrets detected
 *   1 - Secrets found (CI should fail)
 *   2 - Script error
 */

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

// ANSI color codes for terminal output
const colors = {
    red: '\x1b[31m',
    yellow: '\x1b[33m',
    green: '\x1b[32m',
    cyan: '\x1b[36m',
    bold: '\x1b[1m',
    reset: '\x1b[0m'
};

/**
 * Secret Detection Patterns
 * Each pattern includes: regex, description, severity (high/medium/low)
 */
const SECRET_PATTERNS = [
    // AWS/S3 Access Keys
    {
        pattern: /AKIA[0-9A-Z]{16}/g,
        description: 'AWS Access Key ID',
        severity: 'high',
        example: 'AKIAIOSFODNN7EXAMPLE'
    },
    {
        pattern: /aws_secret_access_key\s*=\s*[A-Za-z0-9/+=]{40}/g,
        description: 'AWS Secret Access Key',
        severity: 'high',
        example: 'aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY'
    },

    // Private Keys
    {
        pattern: /-----BEGIN\s+(RSA|EC|OPENSSH|DSA|PGP)\s+PRIVATE KEY-----/g,
        description: 'Private Key',
        severity: 'high',
        example: '-----BEGIN RSA PRIVATE KEY-----'
    },

    // Stripe Keys
    {
        pattern: /sk_live_[0-9a-zA-Z]{24,}/g,
        description: 'Stripe Live Secret Key',
        severity: 'high',
        example: 'sk_live_<your-secret-key-here>'
    },
    {
        pattern: /pk_live_[0-9a-zA-Z]{24,}/g,
        description: 'Stripe Live Publishable Key',
        severity: 'medium',
        example: 'pk_live_<your-publishable-key>'
    },
    {
        pattern: /whsec_[0-9a-zA-Z]{24,}/g,
        description: 'Stripe Webhook Secret',
        severity: 'high',
        example: 'whsec_<your-webhook-secret>'
    },

    // OAuth Secrets (Generic)
    {
        pattern: /client_secret["\s:=]+[a-zA-Z0-9_\-]{32,}/gi,
        description: 'OAuth Client Secret',
        severity: 'high',
        example: 'client_secret: "abc123def456ghi789jkl012mno345pqr"'
    },

    // Google OAuth
    {
        pattern: /[0-9]+-[0-9a-z_]{32}\.apps\.googleusercontent\.com/g,
        description: 'Google OAuth Client ID (safe, but verify)',
        severity: 'low',
        example: '123456789-abcdefghijklmnop.apps.googleusercontent.com'
    },

    // Meta/Facebook Secrets
    {
        pattern: /facebook[_\s]*app[_\s]*secret["\s:=]+[a-f0-9]{32}/gi,
        description: 'Facebook App Secret',
        severity: 'high',
        example: 'facebook_app_secret = "abcdef1234567890abcdef1234567890"'
    },

    // JWT Secrets
    {
        pattern: /jwt[_\s]*secret["\s:=]+.{32,}/gi,
        description: 'JWT Signing Secret',
        severity: 'high',
        example: 'JWT_SECRET = "super-secret-key-minimum-32-characters"'
    },

    // Generic High-Entropy Strings (Base64, Hex)
    {
        pattern: /(?:password|passwd|pwd|secret|token|api[_\s]?key)["\s:=]+([A-Za-z0-9+/=]{40,})/gi,
        description: 'High-Entropy Secret (Base64/Hex)',
        severity: 'medium',
        example: 'api_key = "dGVzdC1zZWNyZXQta2V5LWJhc2U2NC1lbmNvZGVkLXZhbHVl"'
    },

    // Database Connection Strings
    {
        pattern: /postgres:\/\/[^:]+:[^@]+@[^/]+\/\w+/g,
        description: 'PostgreSQL Connection String (with password)',
        severity: 'high',
        example: 'postgres://user:password@localhost:5432/database'
    },

    // Generic Passwords (in config files)
    {
        pattern: /(?:password|passwd|pwd)["\s:=]+(?!.*(?:CHANGE_ME|YOUR_|EXAMPLE|dev_|test_|<[^>]+>|\*\*\*))["']?[a-zA-Z0-9!@#$%^&*()_+\-=]{8,}["']?/gi,
        description: 'Hardcoded Password (not a placeholder)',
        severity: 'medium',
        example: 'password = "MySecretPassword123"'
    },

    // Cloudflare R2 / S3 Secrets
    {
        pattern: /r2\.cloudflarestorage\.com.*[a-zA-Z0-9+/=]{40,}/g,
        description: 'Cloudflare R2 Credential Pattern',
        severity: 'high',
        example: 'https://account.r2.cloudflarestorage.com + secret'
    },

    // LangChain4j / OpenAI / Anthropic API Keys
    {
        pattern: /sk-[a-zA-Z0-9]{48}/g,
        description: 'OpenAI API Key',
        severity: 'high',
        example: 'sk-abcdef1234567890abcdef1234567890abcdef1234567890'
    },
    {
        pattern: /sk-ant-[a-zA-Z0-9\-_]{95}/g,
        description: 'Anthropic API Key',
        severity: 'high',
        example: 'sk-ant-api03-...'
    }
];

/**
 * File Patterns to Exclude from Scanning
 * (Binary files, lock files, generated files, test fixtures)
 */
const EXCLUDED_PATTERNS = [
    /node_modules\//,
    /\.git\//,
    /target\//,
    /dist\//,
    /\.codemachine\//,
    /package-lock\.json$/,
    /\.min\.js$/,
    /\.map$/,
    /\.(png|jpg|jpeg|gif|svg|ico|woff|woff2|ttf|eot)$/,
    /\.env\.example$/,  // Safe - contains placeholders
    /config\/secrets-template\.yaml$/,  // Safe - template only
    /tools\/detect-secrets\.cjs$/,  // This file (contains example patterns)
    /docs\/ops\/security\.md$/,  // Documentation with safe examples
    /docs\/testing\/coverage-verification\.md$/,  // Test documentation with error messages
    /docker-compose\.yml$/,  // Dev environment with safe defaults
    /mvnw$/,  // Maven wrapper (contains MVNW_PASSWORD env var references, not secrets)
    /mvnw\.cmd$/,  // Maven wrapper (Windows)
    /docs\/ops\/dev-services\.md$/  // Documentation with dev credentials
];

/**
 * Safe Placeholder Patterns
 * Strings matching these patterns are ignored (not real secrets)
 */
const SAFE_PLACEHOLDERS = [
    /CHANGE_ME/i,
    /YOUR_[A-Z_]+_HERE/i,
    /EXAMPLE/i,
    /<[^>]+>/,  // XML/HTML tags like <account-id>
    /\*\*\*+/,  // Redacted values
    /dev_[a-z_]+/i,  // Development placeholders
    /test_[a-z_]+/i  // Test placeholders
];

/**
 * Check if a file should be excluded from scanning
 */
function shouldExcludeFile(filePath) {
    return EXCLUDED_PATTERNS.some(pattern => pattern.test(filePath));
}

/**
 * Check if a matched secret is a safe placeholder
 */
function isSafePlaceholder(text) {
    return SAFE_PLACEHOLDERS.some(pattern => pattern.test(text));
}

/**
 * Scan a single file for secrets
 * @returns Array of findings: {line, column, pattern, match, severity}
 */
function scanFile(filePath) {
    if (shouldExcludeFile(filePath)) {
        return [];
    }

    if (!fs.existsSync(filePath)) {
        console.error(`${colors.yellow}Warning: File not found: ${filePath}${colors.reset}`);
        return [];
    }

    const content = fs.readFileSync(filePath, 'utf8');
    const lines = content.split('\n');
    const findings = [];

    lines.forEach((line, lineIndex) => {
        SECRET_PATTERNS.forEach(({ pattern, description, severity }) => {
            const matches = line.matchAll(pattern);

            for (const match of matches) {
                const matchedText = match[0];

                // Skip if it's a safe placeholder
                if (isSafePlaceholder(matchedText)) {
                    continue;
                }

                findings.push({
                    file: filePath,
                    line: lineIndex + 1,
                    column: match.index + 1,
                    pattern: description,
                    match: matchedText,
                    severity: severity,
                    context: line.trim()
                });
            }
        });
    });

    return findings;
}

/**
 * Get list of tracked files from Git
 */
function getTrackedFiles() {
    try {
        const output = execSync('git ls-files', { encoding: 'utf8' });
        return output.split('\n').filter(f => f.trim().length > 0);
    } catch (error) {
        console.error(`${colors.yellow}Warning: Not a Git repository or Git not available. Scanning current directory instead.${colors.reset}`);
        // Fallback: recursively scan current directory
        return getAllFiles('.');
    }
}

/**
 * Recursively get all files in a directory (fallback when Git unavailable)
 */
function getAllFiles(dir, fileList = []) {
    const files = fs.readdirSync(dir);

    files.forEach(file => {
        const filePath = path.join(dir, file);
        const stat = fs.statSync(filePath);

        if (stat.isDirectory()) {
            if (!shouldExcludeFile(filePath + '/')) {
                getAllFiles(filePath, fileList);
            }
        } else {
            fileList.push(filePath);
        }
    });

    return fileList;
}

/**
 * Format findings for terminal output
 */
function formatFindings(findings) {
    if (findings.length === 0) {
        console.log(`${colors.green}${colors.bold}✓ No secrets detected!${colors.reset}`);
        return;
    }

    console.log(`\n${colors.red}${colors.bold}✗ ${findings.length} potential secret(s) detected:${colors.reset}\n`);

    // Group by severity
    const groupedFindings = {
        high: findings.filter(f => f.severity === 'high'),
        medium: findings.filter(f => f.severity === 'medium'),
        low: findings.filter(f => f.severity === 'low')
    };

    ['high', 'medium', 'low'].forEach(severity => {
        const items = groupedFindings[severity];
        if (items.length === 0) return;

        const severityColor = severity === 'high' ? colors.red : severity === 'medium' ? colors.yellow : colors.cyan;
        console.log(`${severityColor}${colors.bold}[${severity.toUpperCase()}]${colors.reset} ${items.length} finding(s):\n`);

        items.forEach(({ file, line, column, pattern, match, context }) => {
            console.log(`  ${colors.bold}${file}:${line}:${column}${colors.reset}`);
            console.log(`  ${colors.cyan}Pattern:${colors.reset} ${pattern}`);
            console.log(`  ${colors.yellow}Match:${colors.reset} ${match.substring(0, 60)}${match.length > 60 ? '...' : ''}`);
            console.log(`  ${colors.cyan}Context:${colors.reset} ${context.substring(0, 100)}${context.length > 100 ? '...' : ''}`);
            console.log('');
        });
    });

    console.log(`${colors.red}${colors.bold}ACTION REQUIRED:${colors.reset}`);
    console.log('  1. Review each finding above');
    console.log('  2. Remove real secrets from code (use .env or Kubernetes secrets)');
    console.log('  3. If false positive, ensure it matches a SAFE_PLACEHOLDER pattern');
    console.log('  4. Re-run: npm run lint:secrets');
    console.log(`\n${colors.yellow}See docs/ops/security.md for secret management guidance.${colors.reset}\n`);
}

/**
 * Main execution
 */
function main() {
    console.log(`${colors.bold}${colors.cyan}Village Homepage - Secret Detection Tool${colors.reset}`);
    console.log(`${colors.cyan}Security Baseline: I1.T9${colors.reset}\n`);

    let filesToScan = [];

    // Check if specific file provided as argument
    if (process.argv.length > 2) {
        const targetFile = process.argv[2];
        if (!fs.existsSync(targetFile)) {
            console.error(`${colors.red}Error: File not found: ${targetFile}${colors.reset}`);
            process.exit(2);
        }
        filesToScan = [targetFile];
        console.log(`Scanning single file: ${targetFile}\n`);
    } else {
        console.log('Scanning all tracked files...\n');
        filesToScan = getTrackedFiles();
    }

    // Scan all files
    let allFindings = [];
    let scannedCount = 0;

    filesToScan.forEach(file => {
        if (!shouldExcludeFile(file)) {
            const findings = scanFile(file);
            allFindings = allFindings.concat(findings);
            scannedCount++;
        }
    });

    console.log(`Scanned ${scannedCount} file(s).\n`);

    // Display results
    formatFindings(allFindings);

    // Exit with error code if secrets found (fails CI)
    if (allFindings.filter(f => f.severity === 'high' || f.severity === 'medium').length > 0) {
        process.exit(1);
    }

    process.exit(0);
}

// Run main function
if (require.main === module) {
    try {
        main();
    } catch (error) {
        console.error(`\n${colors.red}Fatal error during secret detection:${colors.reset}`);
        console.error(error.message);
        if (error.stack) {
            console.error(error.stack);
        }
        process.exit(2);
    }
}

module.exports = { scanFile, SECRET_PATTERNS };
