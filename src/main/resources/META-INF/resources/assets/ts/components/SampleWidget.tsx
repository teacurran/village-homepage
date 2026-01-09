/**
 * SampleWidget.tsx - Example React Island Component
 *
 * This is a placeholder widget demonstrating the React island pattern.
 * It showcases:
 * - TypeScript strict typing
 * - Ant Design component integration
 * - React hooks (useState)
 * - Props validation via Zod (in mounts.ts)
 *
 * This component serves as a template for future widgets (weather, stocks, news feeds, etc.)
 * and will be replaced or extended as the application develops.
 */

import { useState } from 'react';
import { Card, Button, Typography, Space } from 'antd';
import { PlusOutlined, MinusOutlined } from '@ant-design/icons';

const { Title, Text } = Typography;

export interface SampleWidgetProps {
  title: string;
  count?: number | undefined;
}

export default function SampleWidget({ title, count = 0 }: SampleWidgetProps) {
  const [counter, setCounter] = useState(count);

  const increment = () => {
    setCounter((prev) => prev + 1);
  };

  const decrement = () => {
    setCounter((prev) => Math.max(0, prev - 1));
  };

  return (
    <Card
      title={title}
      style={{ maxWidth: 400 }}
      extra={<Text type="secondary">React Island</Text>}
    >
      <Space direction="vertical" style={{ width: '100%' }} size="large">
        <div style={{ textAlign: 'center' }}>
          <Title level={2} style={{ margin: 0 }}>
            {counter}
          </Title>
          <Text type="secondary">Current Count</Text>
        </div>

        <Space style={{ width: '100%', justifyContent: 'center' }}>
          <Button
            type="primary"
            icon={<MinusOutlined />}
            onClick={decrement}
            disabled={counter === 0}
          >
            Decrement
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={increment}>
            Increment
          </Button>
        </Space>

        <div style={{ borderTop: '1px solid #f0f0f0', paddingTop: 16 }}>
          <Text type="secondary" style={{ fontSize: 12 }}>
            This is a sample React island demonstrating Ant Design integration,
            TypeScript strict mode, and the mount registry pattern. Future
            widgets will follow this template.
          </Text>
        </div>
      </Space>
    </Card>
  );
}
