/**
 * Print-friendly backup-codes view (F11 task 4.4 / spec Requirement:
 * Print backup codes with confirmation modal and stripped print view).
 *
 * Renders ONLY when the operator has confirmed Print Anyway in the
 * ConfirmActionModal. The wrapping `<div>` uses a print-only display
 * style: invisible on screen, full-page on the printed paper / PDF.
 *
 * Marcus condition: print view strips operator email, URL, timestamp,
 * QR code — only heading + 10 codes + "store securely" notice. If a
 * print job leaks via OS spooler / network printer, it leaks 10 opaque
 * strings with no account binding.
 */

import { useEffect } from 'react';

interface Props {
  codes: string[];
}

const PRINT_STYLES = `
  @media screen { .platform-print-only { display: none; } }
  @media print {
    body * { visibility: hidden; }
    .platform-print-only, .platform-print-only * { visibility: visible; }
    .platform-print-only {
      position: absolute;
      top: 0;
      left: 0;
      width: 100%;
      padding: 1in;
      font-family: monospace;
      color: #000;
      background: #fff;
    }
    .platform-print-only h1 {
      font-family: sans-serif;
      font-size: 1.25rem;
      margin-bottom: 1rem;
    }
    .platform-print-only ol {
      font-size: 1.25rem;
      line-height: 2;
      margin: 1.5rem 0;
    }
    .platform-print-only p.notice {
      font-family: sans-serif;
      margin-top: 2rem;
      font-style: italic;
    }
  }
`;

export function PrintFriendlyCodes({ codes }: Props) {
  // Inject the print-only stylesheet on mount, remove on unmount. Inline
  // <style> in the same component is acceptable here (and CSP-compatible
  // because the stylesheet is static, not derived from user input).
  useEffect(() => {
    const styleEl = document.createElement('style');
    styleEl.setAttribute('data-platform-print', 'true');
    styleEl.textContent = PRINT_STYLES;
    document.head.appendChild(styleEl);
    return () => {
      document.head.removeChild(styleEl);
    };
  }, []);

  return (
    <div className="platform-print-only" data-testid="platform-print-friendly-codes">
      <h1>Platform Operator Backup Codes</h1>
      <ol>
        {codes.map((code, i) => (
          <li key={i}>{code}</li>
        ))}
      </ol>
      <p className="notice">
        Store securely. These codes can authenticate as your account.
      </p>
    </div>
  );
}
