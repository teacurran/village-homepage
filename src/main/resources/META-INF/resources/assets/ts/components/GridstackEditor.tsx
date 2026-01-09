/**
 * GridstackEditor.tsx - Drag-and-drop homepage layout editor
 *
 * This React component integrates with gridstack.js to provide an interactive
 * drag-and-drop interface for customizing the homepage widget layout.
 *
 * Features:
 * - Drag-and-drop widget repositioning
 * - Widget resizing with min constraints
 * - Auto-save layout changes to preferences API
 * - Responsive grid system (12/6/2 columns)
 * - Widget removal support
 *
 * Integration:
 * - Server renders widgets with data-gs-* attributes
 * - This component initializes gridstack on mount
 * - Layout changes trigger PUT /api/preferences with updated positions
 */

import React, { useEffect, useRef, useState } from 'react';
import { notification } from 'antd';

export interface GridstackEditorProps {
  gridId: string;
  widgetConfigs: Record<string, unknown>;
  saveEndpoint: string;
}

// GridStack types (simplified - full types would come from @types/gridstack)
interface GridStackNode {
  id?: string;
  x: number;
  y: number;
  w: number;
  h: number;
}

interface GridStackOptions {
  column: number;
  cellHeight: string;
  margin: number;
  resizable: {
    handles: string;
  };
  draggable: {
    handle: string;
  };
  disableOneColumnMode?: boolean;
}

interface GridStack {
  on(event: string, callback: (event: Event, items: GridStackNode[]) => void): GridStack;
  compact(): void;
  destroy(removeDOM?: boolean): void;
}

declare global {
  interface Window {
    GridStack?: {
      init(options: GridStackOptions, selector?: string): GridStack;
    };
  }
}

const GridstackEditor: React.FC<GridstackEditorProps> = ({
  gridId,
  widgetConfigs,
  saveEndpoint,
}) => {
  const gridRef = useRef<GridStack | null>(null);
  const [isSaving, setIsSaving] = useState(false);
  const saveTimeoutRef = useRef<NodeJS.Timeout | null>(null);

  useEffect(() => {
    // Wait for gridstack library to load
    if (!window.GridStack) {
      console.error('[GridstackEditor] GridStack library not loaded');
      notification.error({
        message: 'Edit Mode Error',
        description: 'GridStack library failed to load. Please refresh the page.',
      });
      return;
    }

    // Initialize gridstack
    const gridElement = document.getElementById(gridId);
    if (!gridElement) {
      console.error(`[GridstackEditor] Grid element #${gridId} not found`);
      return;
    }

    // GridStack configuration
    const grid = window.GridStack.init(
      {
        column: 12,
        cellHeight: '80px',
        margin: 8,
        resizable: {
          handles: 'e, se, s, sw, w', // Enable all resize handles
        },
        draggable: {
          handle: '.widget-header', // Only drag by header
        },
        disableOneColumnMode: false, // Enable responsive mode
      },
      `#${gridId}`
    );

    gridRef.current = grid;

    // Listen for layout changes
    grid.on('change', handleLayoutChange);

    console.log('[GridstackEditor] Initialized for grid:', gridId);

    // Cleanup on unmount
    return () => {
      if (gridRef.current) {
        gridRef.current.destroy(false); // Don't remove DOM elements
        gridRef.current = null;
      }
      if (saveTimeoutRef.current) {
        clearTimeout(saveTimeoutRef.current);
      }
    };
  }, [gridId]);

  /**
   * Handles layout changes from gridstack drag/resize events
   */
  const handleLayoutChange = (event: Event, items: GridStackNode[]) => {
    console.log('[GridstackEditor] Layout changed:', items);

    // Debounce save to avoid excessive API calls
    if (saveTimeoutRef.current) {
      clearTimeout(saveTimeoutRef.current);
    }

    saveTimeoutRef.current = setTimeout(() => {
      saveLayout(items);
    }, 1000); // Save 1 second after last change
  };

  /**
   * Saves the current layout to the preferences API
   */
  const saveLayout = async (items: GridStackNode[]) => {
    setIsSaving(true);

    try {
      // Transform GridStackNode format to LayoutWidgetType format
      const layout = items.map((item) => ({
        widget_id: item.id || 'unknown',
        widget_type: getWidgetTypeById(item.id || ''),
        x: item.x,
        y: item.y,
        width: item.w,
        height: item.h,
      }));

      // Fetch current preferences
      const getResponse = await fetch(saveEndpoint, {
        method: 'GET',
        credentials: 'include',
      });

      if (!getResponse.ok) {
        throw new Error(`Failed to fetch preferences: ${getResponse.statusText}`);
      }

      const currentPreferences = await getResponse.json();

      // Merge new layout with existing preferences
      const updatedPreferences = {
        ...currentPreferences,
        layout,
      };

      // Save updated preferences
      const putResponse = await fetch(saveEndpoint, {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
        },
        credentials: 'include',
        body: JSON.stringify(updatedPreferences),
      });

      if (!putResponse.ok) {
        throw new Error(`Failed to save preferences: ${putResponse.statusText}`);
      }

      console.log('[GridstackEditor] Layout saved successfully');
      notification.success({
        message: 'Layout Saved',
        description: 'Your homepage layout has been saved.',
        duration: 2,
      });
    } catch (error) {
      console.error('[GridstackEditor] Failed to save layout:', error);
      notification.error({
        message: 'Save Failed',
        description: error instanceof Error ? error.message : 'Could not save layout changes.',
      });
    } finally {
      setIsSaving(false);
    }
  };

  /**
   * Gets widget type from widget ID by reading DOM data attribute
   */
  const getWidgetTypeById = (widgetId: string): string => {
    const element = document.querySelector(`[data-gs-id="${widgetId}"]`);
    if (!element) {
      console.warn(`[GridstackEditor] Widget element not found for ID: ${widgetId}`);
      return 'unknown';
    }

    const contentElement = element.querySelector('[data-widget-type]');
    if (!contentElement) {
      console.warn(`[GridstackEditor] Widget type not found for ID: ${widgetId}`);
      return 'unknown';
    }

    return contentElement.getAttribute('data-widget-type') || 'unknown';
  };

  /**
   * Handles widget removal button clicks
   */
  useEffect(() => {
    const handleRemoveClick = (event: Event) => {
      const target = event.target as HTMLElement;
      if (!target.classList.contains('btn-widget-remove')) {
        return;
      }

      const widgetId = target.getAttribute('data-widget-id');
      if (!widgetId) {
        return;
      }

      // Find the grid item and remove it
      const gridItem = document.querySelector(`[data-gs-id="${widgetId}"]`);
      if (gridItem && gridRef.current) {
        // GridStack will trigger 'change' event which will auto-save
        gridItem.remove();
        notification.info({
          message: 'Widget Removed',
          description: 'The widget has been removed from your homepage.',
          duration: 2,
        });
      }
    };

    document.addEventListener('click', handleRemoveClick);

    return () => {
      document.removeEventListener('click', handleRemoveClick);
    };
  }, []);

  // This component is headless - it just adds behavior to existing DOM
  return (
    <div className="gridstack-editor-indicator">
      {isSaving && (
        <div className="save-indicator">
          <span>Saving...</span>
        </div>
      )}
    </div>
  );
};

export default GridstackEditor;
