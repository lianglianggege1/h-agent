import { handleCopilotRequest } from "@/lib/copilot-runtime";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export const GET = handleCopilotRequest;
export const POST = handleCopilotRequest;
export const PATCH = handleCopilotRequest;
export const DELETE = handleCopilotRequest;
