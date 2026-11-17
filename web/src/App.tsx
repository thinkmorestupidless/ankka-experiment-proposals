import { RouterProvider } from "react-router/dom";
import { createRouter } from "./routes.tsx";

const router = createRouter();

export function App() {
  return <RouterProvider router={router} />;
}
