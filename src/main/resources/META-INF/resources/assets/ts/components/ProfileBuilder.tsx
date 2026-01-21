/**
 * ProfileBuilder.tsx - Profile template editor with device preview
 *
 * This React component provides a unified interface for editing all three profile
 * template types (public_homepage, your_times, your_report). It includes device
 * preview tabs and template-specific editors.
 *
 * Features:
 * - Device preview tabs (Desktop, Tablet, Mobile)
 * - Template-specific editors (conditional rendering)
 * - Auto-save with debouncing
 * - Ant Design UI components
 *
 * Integration:
 * - Server renders mount point with data-mount="ProfileBuilder"
 * - Props validation via Zod schema in mounts.tsx
 * - Saves to PUT /api/profiles/{id}/template
 */

import React, { useState, useEffect, useCallback } from 'react';
import { Card, Tabs, Button, Input, Select, Form, notification, ColorPicker, Switch, Space } from 'antd';
import type { Color } from 'antd/es/color-picker';

const { TabPane } = Tabs;
const { TextArea } = Input;

export interface ProfileBuilderProps {
  profileId: string;
  template: 'public_homepage' | 'your_times' | 'your_report';
  templateConfig: Record<string, unknown>;
  apiEndpoint: string;
  isOwner: boolean;
}

type ViewportSize = 'desktop' | 'tablet' | 'mobile';

const ProfileBuilder: React.FC<ProfileBuilderProps> = ({
  profileId,
  template,
  templateConfig,
  apiEndpoint,
  isOwner,
}) => {
  const [config, setConfig] = useState<Record<string, unknown>>(templateConfig);
  const [activeTab, setActiveTab] = useState<ViewportSize>('desktop');
  const [isSaving, setIsSaving] = useState(false);
  const [form] = Form.useForm();

  const previewSizes: Record<ViewportSize, number> = {
    mobile: 375,
    tablet: 768,
    desktop: 1200,
  };

  // Initialize form with config values
  useEffect(() => {
    form.setFieldsValue(config);
  }, [config, form]);

  // Debounced save function
  const saveConfig = useCallback(async (newConfig: Record<string, unknown>) => {
    setIsSaving(true);

    try {
      const response = await fetch(`${apiEndpoint}/template`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({
          template,
          template_config: newConfig,
        }),
      });

      if (!response.ok) {
        const errorData = await response.json().catch(() => ({}));
        throw new Error(errorData.error || `Save failed: ${response.statusText}`);
      }

      notification.success({
        message: 'Template Saved',
        description: 'Your profile template has been saved.',
        duration: 2,
      });
    } catch (error) {
      console.error('[ProfileBuilder] Save failed:', error);
      notification.error({
        message: 'Save Failed',
        description: error instanceof Error ? error.message : 'Unknown error',
      });
    } finally {
      setIsSaving(false);
    }
  }, [apiEndpoint, template]);

  const handleConfigChange = (field: string, value: unknown) => {
    const newConfig = { ...config, [field]: value };
    setConfig(newConfig);
  };

  const handleSave = () => {
    saveConfig(config);
  };

  if (!isOwner) {
    return null;
  }

  return (
    <div className="profile-builder">
      <Card
        title="Template Editor"
        extra={
          <Button type="primary" onClick={handleSave} loading={isSaving}>
            Save Template
          </Button>
        }
        style={{ marginTop: 24 }}
      >
        {/* Device preview tabs */}
        <Tabs activeKey={activeTab} onChange={(key) => setActiveTab(key as ViewportSize)}>
          <TabPane tab="📱 Mobile" key="mobile" />
          <TabPane tab="💻 Tablet" key="tablet" />
          <TabPane tab="🖥 Desktop" key="desktop" />
        </Tabs>

        {/* Template-specific editors */}
        {template === 'public_homepage' && (
          <PublicHomepageEditor
            config={config}
            onChange={handleConfigChange}
            form={form}
          />
        )}

        {template === 'your_times' && (
          <YourTimesEditor
            config={config}
            onChange={handleConfigChange}
            form={form}
          />
        )}

        {template === 'your_report' && (
          <YourReportEditor
            config={config}
            onChange={handleConfigChange}
            form={form}
          />
        )}

        {/* Preview viewport info */}
        <div style={{ marginTop: 16, color: '#8c8c8c', fontSize: 12 }}>
          Preview width: {previewSizes[activeTab]}px
        </div>
      </Card>
    </div>
  );
};

/**
 * Public Homepage editor (gridstack-based)
 */
interface EditorProps {
  config: Record<string, unknown>;
  onChange: (field: string, value: unknown) => void;
  form: any;
}

const PublicHomepageEditor: React.FC<EditorProps> = ({ config, onChange, form }) => {
  const handleColorChange = (color: Color) => {
    onChange('accent_color', color.toHexString());
  };

  return (
    <Form form={form} layout="vertical" style={{ marginTop: 24 }}>
      <Form.Item label="Header Text" name="header_text">
        <Input
          placeholder="My Custom Homepage"
          onChange={(e) => onChange('header_text', e.target.value)}
        />
      </Form.Item>

      <Form.Item label="Accent Color" name="accent_color">
        <ColorPicker
          value={config.accent_color as string || '#1890ff'}
          onChange={handleColorChange}
          showText
        />
      </Form.Item>

      <div style={{ padding: 16, background: '#fafafa', borderRadius: 4, marginTop: 16 }}>
        <p style={{ margin: 0, color: '#595959' }}>
          Widget layout editing coming soon. Use drag-and-drop on the profile page to arrange widgets.
        </p>
      </div>
    </Form>
  );
};

/**
 * Your Times editor (newspaper slots)
 */
const YourTimesEditor: React.FC<EditorProps> = ({ config, onChange, form }) => {
  const colorSchemes = [
    { label: 'Classic', value: 'classic' },
    { label: 'Modern', value: 'modern' },
    { label: 'Minimal', value: 'minimal' },
  ];

  return (
    <Form form={form} layout="vertical" style={{ marginTop: 24 }}>
      <Form.Item label="Masthead Text" name="masthead_text">
        <Input
          placeholder="The Daily Times"
          onChange={(e) => onChange('masthead_text', e.target.value)}
        />
      </Form.Item>

      <Form.Item label="Tagline" name="tagline">
        <Input
          placeholder="All the news that's fit to curate"
          onChange={(e) => onChange('tagline', e.target.value)}
        />
      </Form.Item>

      <Form.Item label="Color Scheme" name="color_scheme">
        <Select
          options={colorSchemes}
          onChange={(value) => onChange('color_scheme', value)}
          placeholder="Select color scheme"
        />
      </Form.Item>

      <div style={{ padding: 16, background: '#fafafa', borderRadius: 4, marginTop: 16 }}>
        <p style={{ margin: 0, color: '#595959', fontSize: 14 }}>
          <strong>Slot Assignment:</strong> Use the article picker on your profile page to assign
          articles to slots (main headline, secondary stories, sidebar).
        </p>
      </div>
    </Form>
  );
};

/**
 * Your Report editor (three-column layout)
 */
const YourReportEditor: React.FC<EditorProps> = ({ config, onChange, form }) => {
  return (
    <Form form={form} layout="vertical" style={{ marginTop: 24 }}>
      <Form.Item label="Main Header" name="main_header">
        <Input
          placeholder="Weekly Report"
          onChange={(e) => onChange('main_header', e.target.value)}
        />
      </Form.Item>

      <Form.Item label="Headline Photo URL" name="headline_photo_url">
        <Input
          placeholder="https://example.com/image.jpg"
          onChange={(e) => onChange('headline_photo_url', e.target.value)}
        />
      </Form.Item>

      <Form.Item label="Style" name="uppercase_style">
        <Space>
          <Switch
            checked={config.uppercase_style as boolean || false}
            onChange={(checked) => onChange('uppercase_style', checked)}
          />
          <span>Uppercase headlines</span>
        </Space>
      </Form.Item>

      <div style={{ padding: 16, background: '#fafafa', borderRadius: 4, marginTop: 16 }}>
        <p style={{ margin: 0, color: '#595959', fontSize: 14 }}>
          <strong>Column Management:</strong> Use the column editor on your profile page to add
          sections and assign articles to columns.
        </p>
      </div>
    </Form>
  );
};

export default ProfileBuilder;
