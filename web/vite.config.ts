// The app in src/ is built into dist/; Vite names every built file by its content under assets/, so
// a page from an old build still finds its files during a rollout (the server marks them immutable).
import { defineConfig } from "vite";

export default defineConfig({
  build: { outDir: "dist", assetsDir: "assets" },
});
