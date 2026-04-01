import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './global.css'
import App from './App'

// Request persistent storage to protect IndexedDB offline queue from browser eviction.
// Mobile browsers can silently wipe storage under memory pressure — this asks the
// browser to keep our data. If denied, the queue still works but is not eviction-proof.
if (navigator.storage?.persist) {
  navigator.storage.persist().then((granted) => {
    if (!granted) {
      console.warn('[FABT] Persistent storage not granted — offline queue may be evicted under storage pressure');
    }
  });
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
