// The interface's routes (contracts/interface.md). The admission gate is the shell's, around all.
import { createBrowserRouter } from "react-router";
import { Shell } from "./shell/Shell.tsx";
import { Requests } from "./areas/Requests.tsx";
import { Request } from "./areas/Request.tsx";
import { Queue } from "./areas/Queue.tsx";
import { Sources } from "./areas/Sources.tsx";
import { Chat } from "./areas/Chat.tsx";

export const routes = [
  {
    path: "/",
    Component: Shell,
    children: [
      { index: true, Component: Requests },
      { path: "requests/:id", Component: Request },
      { path: "requests/:id/chat", Component: Chat },
      { path: "queue", Component: Queue },
      { path: "sources", Component: Sources },
      { path: "chat", Component: Chat },
      { path: "*", Component: Requests },
    ],
  },
];

export const createRouter = () => createBrowserRouter(routes);
