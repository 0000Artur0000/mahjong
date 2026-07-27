import { setupServer } from "msw/node";
import { handlers } from "./handlers";

// Node server for tests.
export const server = setupServer(...handlers);
