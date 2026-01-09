#!/usr/bin/env node
/**
 * esbuild.config.js - TypeScript/React bundler configuration
 *
 * This script bundles TypeScript source files from src/main/resources/META-INF/resources/assets/ts
 * into production-ready JavaScript bundles in src/main/resources/META-INF/resources/assets/js.
 *
 * Features:
 * - Production builds: minified, tree-shaken, content-hashed bundles with source maps
 * - Watch mode: incremental rebuilds for fast development iteration
 * - React 18 JSX transformation (automatic runtime)
 * - Path aliases (@/* resolves to TypeScript source root)
 * - Bundle splitting for vendor dependencies (React, Ant Design, gridstack)
 *
 * Entry Point:
 *   mounts.tsx - Bootstraps all React islands via the component registry
 *
 * Usage:
 *   npm run build        # Production build (invoked by Maven during compile phase)
 *   npm run watch        # Development watch mode (run in separate terminal)
 *   node esbuild.config.js --watch  # Alternative watch invocation
 */

const esbuild = require('esbuild');
const path = require('path');
const fs = require('fs');

// Determine if we're in watch mode from CLI args
const isWatch = process.argv.includes('--watch');
const isProd = process.env.NODE_ENV === 'production' || !isWatch;

// Define input/output directories
const tsSourceDir = path.resolve(__dirname, 'src/main/resources/META-INF/resources/assets/ts');
const jsOutputDir = path.resolve(__dirname, 'src/main/resources/META-INF/resources/assets/js');

// Ensure output directory exists
if (!fs.existsSync(jsOutputDir)) {
  fs.mkdirSync(jsOutputDir, { recursive: true });
}

// esbuild configuration
const buildOptions = {
  // Entry point: mounts.tsx bootstraps all React islands
  entryPoints: [path.join(tsSourceDir, 'mounts.tsx')],

  // Output configuration
  bundle: true,
  outdir: jsOutputDir,
  format: 'esm',
  target: ['es2022', 'chrome120', 'firefox120', 'safari17'],
  platform: 'browser',

  // Code splitting for vendor chunks
  splitting: true,
  chunkNames: 'chunks/[name]-[hash]',

  // Asset naming (content hashing for cache busting)
  entryNames: isProd ? '[name]-[hash]' : '[name]',
  assetNames: 'assets/[name]-[hash]',

  // Source maps (inline for dev, external for prod)
  sourcemap: isProd ? true : 'inline',

  // Minification and tree-shaking
  minify: isProd,
  treeShaking: true,

  // JSX configuration (React 18 automatic runtime)
  jsx: 'automatic',
  jsxDev: !isProd,

  // Path resolution
  resolveExtensions: ['.tsx', '.ts', '.jsx', '.js', '.json'],
  alias: {
    '@': tsSourceDir,
  },

  // External dependencies (none - bundle everything for simplicity)
  // In a more advanced setup, you might externalize React/ReactDOM if using import maps
  external: [],

  // Metadata for bundle analysis
  metafile: true,

  // Log level
  logLevel: 'info',

  // Loader configuration
  loader: {
    '.ts': 'tsx',   // Treat .ts as TSX to support JSX in .ts files
    '.tsx': 'tsx',
    '.png': 'file',
    '.jpg': 'file',
    '.jpeg': 'file',
    '.svg': 'file',
    '.woff': 'file',
    '.woff2': 'file',
    '.ttf': 'file',
    '.eot': 'file',
  },

  // Custom banner/footer (optional - for injecting polyfills or runtime config)
  // banner: { js: '/* Village Homepage - Built with esbuild */' },

  // Define environment variables available in bundled code
  define: {
    'process.env.NODE_ENV': JSON.stringify(isProd ? 'production' : 'development'),
  },
};

/**
 * Write manifest.json mapping logical names to hashed bundles
 * (useful for server-side template integration)
 */
function writeManifest(metafile) {
  const manifest = {};
  for (const [outputPath, output] of Object.entries(metafile.outputs)) {
    if (output.entryPoint) {
      const logicalName = path.basename(output.entryPoint, path.extname(output.entryPoint));
      const publicPath = '/' + path.relative(
        path.join(__dirname, 'src/main/resources/META-INF/resources'),
        outputPath
      ).replace(/\\/g, '/');
      manifest[logicalName] = publicPath;
    }
  }

  const manifestPath = path.join(jsOutputDir, 'manifest.json');
  fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2));
  console.log(`✅ Manifest written to ${manifestPath}`);
}

/**
 * Main build function
 */
async function build() {
  try {
    if (isWatch) {
      console.log('🔄 Starting esbuild in watch mode...');
      console.log(`   Source: ${tsSourceDir}`);
      console.log(`   Output: ${jsOutputDir}`);

      const ctx = await esbuild.context(buildOptions);

      await ctx.watch();
      console.log('👀 Watching for changes...');

      // Keep process alive in watch mode
      process.on('SIGINT', async () => {
        console.log('\n🛑 Stopping watch mode...');
        await ctx.dispose();
        process.exit(0);
      });
    } else {
      console.log('🏗️  Building TypeScript assets for production...');
      console.log(`   Source: ${tsSourceDir}`);
      console.log(`   Output: ${jsOutputDir}`);

      const result = await esbuild.build(buildOptions);

      // Write manifest for server-side integration
      if (result.metafile) {
        writeManifest(result.metafile);
      }

      console.log('✅ Build complete!');

      // Analyze bundle size (optional - can be disabled in CI)
      if (result.metafile && !process.env.CI) {
        const analysis = await esbuild.analyzeMetafile(result.metafile, {
          verbose: false,
        });
        console.log('\n📊 Bundle Analysis:\n');
        console.log(analysis);
      }

      process.exit(0);
    }
  } catch (error) {
    console.error('❌ Build failed:', error);
    process.exit(1);
  }
}

// Run build
build();
