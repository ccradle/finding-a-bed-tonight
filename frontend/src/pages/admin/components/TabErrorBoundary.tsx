import { Component, type ReactNode } from 'react';
import { color } from '../../../theme/colors';
import { text, weight } from '../../../theme/typography';

interface Props {
  children: ReactNode;
  tabName: string;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

/**
 * Error boundary for lazy-loaded admin tabs. Catches render errors
 * and displays a recovery message instead of crashing the entire
 * admin panel. The tab bar remains functional — the user can switch
 * to another tab or refresh.
 */
export class TabErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo) {
    console.error(`Admin tab "${this.props.tabName}" crashed:`, error, info.componentStack);
  }

  render() {
    if (this.state.hasError) {
      return (
        <div
          role="alert"
          style={{
            padding: 32,
            textAlign: 'center',
            backgroundColor: color.errorBg,
            borderRadius: 12,
            border: `1px solid ${color.errorBorder}`,
          }}
        >
          <div style={{ fontSize: text.lg, fontWeight: weight.bold, color: color.error, marginBottom: 8 }}>
            Something went wrong loading this tab
          </div>
          <div style={{ fontSize: text.sm, color: color.textTertiary, marginBottom: 16 }}>
            {this.state.error?.message || 'An unexpected error occurred'}
          </div>
          <button
            onClick={() => this.setState({ hasError: false, error: null })}
            style={{
              padding: '10px 20px',
              backgroundColor: color.primary,
              color: color.textInverse,
              border: 'none',
              borderRadius: 8,
              fontSize: text.base,
              fontWeight: weight.semibold,
              cursor: 'pointer',
              minHeight: 44,
            }}
          >
            Try Again
          </button>
        </div>
      );
    }

    return this.props.children;
  }
}
