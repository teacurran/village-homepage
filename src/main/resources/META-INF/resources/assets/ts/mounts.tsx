/**
 * mounts.tsx - React Island Mount Registry
 *
 * This file is the entry point for all React islands in the Village Homepage application.
 * It implements the "islands architecture" pattern where server-rendered Qute templates
 * contain isolated React components that hydrate on the client side.
 *
 * ## How It Works
 *
 * 1. Server-side (Qute templates) renders placeholder elements with:
 *    - `data-mount` attribute specifying the component name
 *    - `data-props` attribute with JSON-serialized props
 *
 * 2. Client-side (this file):
 *    - Scans DOM for elements with `data-mount` attribute
 *    - Looks up component in the registry
 *    - Validates props using Zod schemas
 *    - Hydrates React component into the placeholder
 *
 * ## Example Usage in Qute Templates
 *
 * ```html
 * <div data-mount="WeatherWidget"
 *      data-props='{"location": "San Francisco", "units": "metric"}'>
 * </div>
 * ```
 *
 * ## Adding New Components
 *
 * 1. Create component in src/main/resources/META-INF/resources/assets/ts/components/
 * 2. Define props schema using Zod
 * 3. Register in COMPONENT_REGISTRY below
 * 4. Component will auto-mount when DOM element with matching data-mount is found
 */

import { createRoot } from 'react-dom/client';
import { z } from 'zod';

// Import components
import SampleWidget from './components/SampleWidget';
import GridstackEditor from './components/GridstackEditor';

/**
 * Component registry mapping data-mount attribute values to React components
 * and their corresponding prop validation schemas.
 */
const COMPONENT_REGISTRY = {
  SampleWidget: {
    component: SampleWidget,
    propsSchema: z.object({
      title: z.string(),
      count: z.number().int().nonnegative().optional(),
    }),
  },
  GridstackEditor: {
    component: GridstackEditor,
    propsSchema: z.object({
      gridId: z.string(),
      widgetConfigs: z.record(z.unknown()),
      saveEndpoint: z.string(),
    }),
  },
  // Add additional components here as they are developed:
  // WeatherWidget: {
  //   component: WeatherWidget,
  //   propsSchema: WeatherWidgetPropsSchema,
  // },
} as const;

type ComponentName = keyof typeof COMPONENT_REGISTRY;

/**
 * Parse and validate props from data-props attribute
 */
function parseProps<T>(
  propsJson: string | null,
  schema: z.ZodSchema<T>,
  componentName: string
): T | null {
  if (!propsJson) {
    console.warn(`[mounts] No data-props found for ${componentName}`);
    return null;
  }

  try {
    const rawProps = JSON.parse(propsJson) as unknown;
    const validatedProps = schema.parse(rawProps);
    return validatedProps;
  } catch (error) {
    if (error instanceof z.ZodError) {
      console.error(
        `[mounts] Invalid props for ${componentName}:`,
        error.errors
      );
    } else if (error instanceof SyntaxError) {
      console.error(
        `[mounts] Invalid JSON in data-props for ${componentName}:`,
        error.message
      );
    } else {
      console.error(`[mounts] Error parsing props for ${componentName}:`, error);
    }
    return null;
  }
}

/**
 * Mount a single React component into a DOM element
 */
function mountComponent(element: Element, componentName: ComponentName): void {
  const registration = COMPONENT_REGISTRY[componentName];
  if (!registration) {
    console.error(
      `[mounts] Component "${componentName}" not found in registry`
    );
    return;
  }

  const { component: Component, propsSchema } = registration;
  const propsJson = element.getAttribute('data-props');
  const props = parseProps(propsJson, propsSchema, componentName);

  if (props === null) {
    console.error(
      `[mounts] Failed to parse props for ${componentName}, skipping mount`
    );
    return;
  }

  try {
    const root = createRoot(element);
    root.render(<Component {...props} />);
    console.log(`[mounts] ✅ Mounted ${componentName}`, props);
  } catch (error) {
    console.error(`[mounts] Failed to mount ${componentName}:`, error);
  }
}

/**
 * Scan the DOM and mount all registered React islands
 */
function mountAll(): void {
  const mountElements = document.querySelectorAll('[data-mount]');

  if (mountElements.length === 0) {
    console.log('[mounts] No React islands found in DOM');
    return;
  }

  console.log(`[mounts] Found ${mountElements.length} React island(s) to mount`);

  mountElements.forEach((element) => {
    const componentName = element.getAttribute('data-mount');

    if (!componentName) {
      console.warn('[mounts] Element with data-mount has no component name');
      return;
    }

    if (!(componentName in COMPONENT_REGISTRY)) {
      console.error(
        `[mounts] Unknown component "${componentName}" - not in registry`
      );
      return;
    }

    mountComponent(element, componentName as ComponentName);
  });
}

/**
 * Initialize React islands when DOM is ready
 */
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', mountAll);
} else {
  // DOM already loaded (e.g., script loaded after page render)
  mountAll();
}

/**
 * Export for programmatic mounting (if needed for dynamic content)
 */
export { mountAll, mountComponent, COMPONENT_REGISTRY };
