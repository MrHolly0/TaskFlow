
  import { createRoot } from "react-dom/client";
  import App from "./app/App.tsx";
  import "./styles/index.css";
  import { registerServiceWorker } from "./lib/registerServiceWorker";

  registerServiceWorker();
  createRoot(document.getElementById("root")!).render(<App />);
  