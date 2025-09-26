import { Toaster } from "sonner";
import { Routes, Route, Navigate } from "react-router-dom";
import TopNav from "@/components/common/TopNav";
import Simulacion from "@/pages/Simulacion";
import Operacion from "@/pages/Operacion";
import Colapso from "./pages/Colapso";
import Registrar from "./pages/Registrar";
export default function App() {

  return (
    <div className="min-h-screen bg-transparent">
      <TopNav />
      <main>
        <Routes>
          <Route path="/" element={<Navigate to="/operacion" replace />} />
          <Route path="/registrar" element={<Registrar />} />
          <Route path="/operacion" element={<Operacion />} />
          <Route path="/simulacion" element={<Simulacion />} />
          <Route path="/colapso" element={<Colapso />} />
          <Route path="*" element={<Navigate to="/operacion" replace />} />
        </Routes>
        <Toaster richColors />
      </main>
    </div>
  );
}
