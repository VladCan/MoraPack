import { StrictMode } from 'react'
import { BrowserRouter } from "react-router-dom";
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from './components/ui/theme-provider.tsx';

// Crea un único QueryClient para toda la app
console.log(import.meta.env.VITE_API_BASE_URL)
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,   // los datos se consideran frescos 30s
      retry: 2,            // reintentar 2 veces en errores de red/5xx
      refetchOnWindowFocus: false,
    },
    mutations: {
      retry: 0,            // no reintentes en mutations por defecto
    },
  },
});
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="dark" storageKey="vite-ui-theme">
          <App />
        </ThemeProvider>
        {/* <ReactQueryDevtools initialIsOpen={false} /> */}
      </QueryClientProvider>
    </BrowserRouter>
  </StrictMode>,
)
