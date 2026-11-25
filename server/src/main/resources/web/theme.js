// Applies the chosen look before the first paint, so a dark page never flashes light.
try { const t = localStorage.getItem('unison.theme'); if (t === 'light' || t === 'dark') document.documentElement.dataset.theme = t; } catch (e) {}
