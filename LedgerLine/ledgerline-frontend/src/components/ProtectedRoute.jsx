import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { LoadingState } from "./Ui";

export default function ProtectedRoute({ roles }) {
  const { user, initializing } = useAuth();
  const location = useLocation();

  if (initializing) return <LoadingState label="Checking your secure session…" />;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  if (roles && !roles.includes(user.role)) return <Navigate to="/dashboard" replace />;
  return <Outlet />;
}

