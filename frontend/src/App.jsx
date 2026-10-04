import { lazy, Suspense } from "react";
import { Navigate, Route, Routes } from "react-router-dom";
import { useAuth } from "./store/authStore";
import Layout from "./components/Layout";
import Landing from "./pages/Landing";
import Login from "./pages/Login";
import Magic from "./pages/Magic";
import Debts from "./pages/Debts";
import Repact from "./pages/Repact";
import Pay from "./pages/Pay";
import Pasarela from "./pages/Pasarela";
import Vencimientos from "./pages/Vencimientos";
import Historial from "./pages/Historial";
import MisDatos from "./pages/MisDatos";
import ComoFunciona from "./pages/ComoFunciona";
import PagosRecibidos from "./pages/PagosRecibidos";
import EnRiesgo from "./pages/EnRiesgo";
import Cargar from "./pages/Cargar";
import Claves from "./pages/Claves";

//  El portal de empresas trae los graficos (recharts), que pesan mas que todo
//  el resto junto. Se descarga solo al entrar ahi: el deudor nunca lo baja.
const DataBridge = lazy(() => import("./pages/DataBridge"));
const cargando = <div className="main">Cargando Technical Bridge…</div>;

function Guard({ children, role }) {
  const { user, ready, homeFor } = useAuth();
  if (!ready) return cargando;
  if (!user) return <Navigate to="/" replace />;
  if (role && user.role !== role) return <Navigate to={homeFor(user)} replace />;
  return children;
}

function PublicOnly({ children }) {
  const { user, ready, homeFor } = useAuth();
  if (!ready) return cargando;
  if (user) return <Navigate to={homeFor(user)} replace />;
  return children;
}

export default function App() {
  return (
    <Routes>
      <Route
        path="/"
        element={
          <PublicOnly>
            <Landing />
          </PublicOnly>
        }
      />
      <Route
        path="/login"
        element={
          <PublicOnly>
            <Login portal="TB" />
          </PublicOnly>
        }
      />
      <Route
        path="/databridge/login"
        element={
          <PublicOnly>
            <Login portal="DATABRIDGE" />
          </PublicOnly>
        }
      />
      <Route path="/magic" element={<Magic />} />
      <Route path="/pasarela/:id" element={<Pasarela />} />
      <Route
        path="/app"
        element={
          <Guard role="DEBTOR">
            <Layout variant="debtor" />
          </Guard>
        }
      >
        <Route index element={<Debts />} />
        <Route path="repactar/:id" element={<Repact />} />
        <Route path="pagar/:id" element={<Pay />} />
        <Route path="vencimientos" element={<Vencimientos />} />
        <Route path="pagos" element={<Historial />} />
        <Route path="mis-datos" element={<MisDatos />} />
        <Route path="como-funciona" element={<ComoFunciona para="deudor" />} />
      </Route>
      <Route
        path="/databridge"
        element={
          <Guard role="CREDITOR">
            <Layout variant="creditor" />
          </Guard>
        }
      >
        <Route index element={<Suspense fallback={cargando}><DataBridge /></Suspense>} />
        <Route path="pagos" element={<PagosRecibidos />} />
        <Route path="en-riesgo" element={<EnRiesgo />} />
        <Route path="cargar" element={<Cargar />} />
        <Route path="claves" element={<Claves />} />
        <Route path="como-funciona" element={<ComoFunciona para="empresa" />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
