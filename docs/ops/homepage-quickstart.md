# Homepage Quick Start Guide

This guide provides instructions for running and customizing the Village Homepage Experience Shell.

## Table of Contents

- [Running the Homepage](#running-the-homepage)
- [Edit Mode](#edit-mode)
- [Theme Tokens](#theme-tokens)
- [Widget System](#widget-system)
- [Feature Flags](#feature-flags)
- [Development Workflow](#development-workflow)

---

## Running the Homepage

### Prerequisites

- Java 21+
- Maven 3.8+
- Node.js 18+ (for frontend assets)
- PostgreSQL 17 (or H2 for testing)

### Development Mode

1. **Start the Quarkus dev server:**

   ```bash
   ./mvnw quarkus:dev
   ```

   This starts the application on `http://localhost:8080` with hot reload enabled.

2. **Build frontend assets (in watch mode):**

   In a separate terminal:

   ```bash
   npm run watch
   ```

   This runs esbuild in watch mode, automatically rebuilding TypeScript/React assets on change.

3. **Access the homepage:**

   Open `http://localhost:8080/` in your browser.

### Production Build

1. **Build frontend assets:**

   ```bash
   npm run build
   ```

2. **Build the application:**

   ```bash
   ./mvnw clean package
   ```

3. **Run the packaged application:**

   ```bash
   java -jar target/quarkus-app/quarkus-run.jar
   ```

---

## Edit Mode

Edit mode allows authenticated users to customize their homepage layout via drag-and-drop.

### Enabling Edit Mode

1. **Login:** Click "Login" in the header and authenticate via Google, Facebook, or Apple.

2. **Toggle Edit Mode:** Click the "Customize" button in the header. The URL will change to `/?edit=true`.

3. **Customize Layout:**
   - **Drag widgets:** Click and drag the widget header to reposition
   - **Resize widgets:** Drag the resize handles on widget edges
   - **Remove widgets:** Click the "×" button in the widget header

4. **Exit Edit Mode:** Click "Done Editing" to return to view mode.

### Auto-Save

Layout changes are automatically saved to your preferences after 1 second of inactivity. You'll see a "Saving..." indicator in the bottom-right corner when changes are being persisted.

### Anonymous Users

Anonymous users cannot access edit mode. They see a default homepage layout. To customize, they must login first.

---

## Theme Tokens

The homepage uses CSS custom properties (CSS variables) for theming, allowing easy customization and dark mode support.

### Theme Modes

The `theme.mode` preference controls the color scheme:

- **`light`**: Always use light theme
- **`dark`**: Always use dark theme
- **`system`**: Follow browser/OS preference (default)

Theme mode is set via the `data-theme` attribute on the `<html>` element.

### Contrast Modes

The `theme.contrast` preference controls accessibility:

- **`standard`**: Normal contrast (default)
- **`high`**: High contrast for accessibility

Contrast mode is set via the `data-contrast` attribute on the `<html>` element.

### Custom Accent Color

Users can set a custom accent color via the `theme.accent` preference (hex format: `#RRGGBB`).

When set, the homepage includes an inline `<style>` tag:

```html
<style>
  :root {
    --accent-color: #ff5733;
  }
</style>
```

This overrides the default `--accent-color` CSS variable.

### Theme Token Reference

The following CSS custom properties are available for styling:

#### Color Tokens

| Token                     | Light Mode    | Dark Mode     | Description              |
|---------------------------|---------------|---------------|--------------------------|
| `--color-bg-primary`      | `#ffffff`     | `#1a1a1a`     | Primary background       |
| `--color-bg-secondary`    | `#f5f5f5`     | `#2a2a2a`     | Secondary background     |
| `--color-bg-tertiary`     | `#e8e8e8`     | `#3a3a3a`     | Tertiary background      |
| `--color-text-primary`    | `#1a1a1a`     | `#ffffff`     | Primary text color       |
| `--color-text-secondary`  | `#666666`     | `#b3b3b3`     | Secondary text color     |
| `--color-text-tertiary`   | `#999999`     | `#808080`     | Tertiary text color      |
| `--color-border`          | `#d9d9d9`     | `#404040`     | Border color             |
| `--color-border-light`    | `#e8e8e8`     | `#333333`     | Light border color       |
| `--color-accent`          | `#1890ff`     | `#1890ff`     | Accent color (overridable) |
| `--color-accent-hover`    | `#40a9ff`     | `#40a9ff`     | Accent hover state       |
| `--color-accent-active`   | `#096dd9`     | `#096dd9`     | Accent active state      |

#### Spacing Tokens

| Token            | Value  |
|------------------|--------|
| `--spacing-xs`   | `4px`  |
| `--spacing-sm`   | `8px`  |
| `--spacing-md`   | `16px` |
| `--spacing-lg`   | `24px` |
| `--spacing-xl`   | `32px` |

#### Typography Tokens

| Token                 | Value                              |
|-----------------------|------------------------------------|
| `--font-family`       | System font stack                  |
| `--font-size-xs`      | `12px`                             |
| `--font-size-sm`      | `14px`                             |
| `--font-size-base`    | `16px`                             |
| `--font-size-lg`      | `18px`                             |
| `--font-size-xl`      | `24px`                             |
| `--font-size-2xl`     | `32px`                             |
| `--line-height-tight` | `1.25`                             |
| `--line-height-normal`| `1.5`                              |
| `--line-height-relaxed`| `1.75`                            |

#### Other Tokens

| Token               | Value                  |
|---------------------|------------------------|
| `--radius-sm`       | `4px`                  |
| `--radius-md`       | `8px`                  |
| `--radius-lg`       | `12px`                 |
| `--transition-fast` | `150ms ease`           |
| `--transition-base` | `250ms ease`           |
| `--transition-slow` | `350ms ease`           |
| `--shadow-sm`       | Light/dark dependent   |
| `--shadow-md`       | Light/dark dependent   |
| `--shadow-lg`       | Light/dark dependent   |

---

## Widget System

### Available Widget Types

The homepage supports the following widget types:

| Widget Type     | Description                            | Feature Flag Required      |
|-----------------|----------------------------------------|----------------------------|
| `search_bar`    | Global search input                    | None                       |
| `news_feed`     | RSS feed aggregation with AI tagging   | None                       |
| `weather`       | Weather widget (Open-Meteo/NWS)        | None                       |
| `stocks`        | Stock market data (Alpha Vantage)      | `stocks_widget`            |
| `social_feed`   | Instagram/Facebook integration         | `social_integration`       |
| `rss_feed`      | Custom RSS feed widget                 | None                       |
| `quick_links`   | User-defined link shortcuts            | None                       |

### Widget Layout Structure

Each widget in the layout has the following properties:

```json
{
  "widget_id": "news",           // Unique identifier
  "widget_type": "news_feed",    // Widget type
  "x": 0,                        // Column offset (0-based)
  "y": 2,                        // Row offset (0-based)
  "width": 8,                    // Width in columns
  "height": 6                    // Height in rows
}
```

### Grid System

The homepage uses gridstack.js with a responsive grid:

- **Desktop:** 12 columns
- **Tablet:** 6 columns (auto-adjusts at breakpoint)
- **Mobile:** 2 columns (auto-adjusts at breakpoint)

Each grid cell is `80px` tall with `8px` margin.

### Widget Constraints

- **Minimum width:** 2 columns
- **Minimum height:** 2 rows
- **Maximum width:** 12 columns (desktop)

---

## Feature Flags

Feature flags control which widgets are available to users based on rollout configuration.

### Flag-Gated Widgets

- **`stocks_widget`**: Controls visibility of `stocks` widget
- **`social_integration`**: Controls visibility of `social_feed` widget

### Viewing Feature Flags

Feature flags are managed via the admin API:

```bash
curl http://localhost:8080/admin/api/feature-flags
```

### Enabling a Feature Flag

```bash
curl -X PATCH http://localhost:8080/admin/api/feature-flags/stocks_widget \
  -H "Content-Type: application/json" \
  -d '{
    "enabled": true,
    "rollout_percentage": 100
  }'
```

### Feature Flag Behavior

- If a widget's feature flag is **disabled**, the widget is filtered out during homepage rendering
- If a widget's feature flag is **enabled** but rollout percentage is < 100, only cohorted users see it
- Cohort assignment is stable per user (based on MD5 hash)

---

## Development Workflow

### Adding a New Widget

1. **Define Widget Type:** Add the new widget type to `LayoutWidgetType` documentation

2. **Update Template:** Add a new case in the Qute template's widget switch statement:

   ```html
   {#case "my_new_widget"}
   <div class="widget-placeholder">
       <p>Loading my new widget...</p>
   </div>
   ```

3. **Create React Component (Optional):** If the widget needs client-side interactivity:

   ```typescript
   // src/main/resources/META-INF/resources/assets/ts/components/MyNewWidget.tsx
   import React from 'react';

   export interface MyNewWidgetProps {
     config: Record<string, unknown>;
   }

   const MyNewWidget: React.FC<MyNewWidgetProps> = ({ config }) => {
     return <div>My New Widget</div>;
   };

   export default MyNewWidget;
   ```

4. **Register Component:** Add to `mounts.tsx` registry:

   ```typescript
   import MyNewWidget from './components/MyNewWidget';

   const COMPONENT_REGISTRY = {
     // ...
     MyNewWidget: {
       component: MyNewWidget,
       propsSchema: z.object({
         config: z.record(z.unknown()),
       }),
     },
   };
   ```

5. **Update Template (React Island):** Replace placeholder with React mount point:

   ```html
   {#case "my_new_widget"}
   <div data-mount="MyNewWidget"
        data-props='{"config": {widget.widgetConfig}}'>
   </div>
   ```

6. **Build Assets:** Run `npm run build` to bundle the new component

7. **Test:** Verify the widget renders correctly and hydrates properly

### Modifying Theme Tokens

1. **Edit CSS:** Update `/assets/css/homepage.css`

2. **Update Documentation:** Add new tokens to this guide's Theme Token Reference section

3. **Test:** Verify tokens work in light, dark, and high contrast modes

### Running Tests

```bash
# Run all tests
./mvnw test

# Run homepage-specific tests
./mvnw test -Dtest=HomepageResourceTest

# Run with coverage
./mvnw test jacoco:report
```

### Debugging Tips

- **SSR Issues:** Check Qute template syntax errors in Quarkus dev console
- **React Hydration Failures:** Open browser console and look for `[mounts]` log messages
- **Feature Flag Not Working:** Verify flag exists in database and is enabled
- **Styles Not Applying:** Check CSS custom property cascade and data-theme/data-contrast attributes
- **Layout Not Saving:** Check browser console for API errors and verify `/api/preferences` endpoint is accessible

---

## Troubleshooting

### Widgets Not Appearing

1. **Check feature flags:** Ensure required flags are enabled
2. **Check preferences:** Verify widget is in user's layout array
3. **Check browser console:** Look for JavaScript errors

### Edit Mode Not Working

1. **Check authentication:** Edit mode requires login
2. **Check gridstack.js:** Verify CDN script loaded (check Network tab)
3. **Check GridstackEditor:** Verify React component mounted (check console logs)

### Theme Not Applying

1. **Check HTML attributes:** Verify `data-theme` and `data-contrast` are set
2. **Check CSS file:** Ensure `/assets/css/homepage.css` loaded successfully
3. **Check custom accent:** If set, verify inline style tag exists

### React Islands Not Hydrating

1. **Check script tag:** Verify `mounts-[hash].js` is included and loads
2. **Check data-mount:** Ensure element has correct `data-mount` attribute
3. **Check data-props:** Verify JSON is valid and matches schema
4. **Check registry:** Ensure component is registered in `COMPONENT_REGISTRY`

---

## Additional Resources

- [Project Standards](../../CLAUDE.md)
- [Preferences Schema](../architecture/preferences-schema.md)
- [Feature Flags Service](../../src/main/java/villagecompute/homepage/services/FeatureFlagService.java)
- [React Islands Architecture](../../src/main/resources/META-INF/resources/assets/ts/mounts.tsx)
- [Gridstack.js Documentation](https://gridstackjs.com/)

---

**Last Updated:** 2025-01-09
**Version:** 1.0.0
**Iteration:** I2.T6
