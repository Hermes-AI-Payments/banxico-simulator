#!/usr/bin/env node
// Servidor MCP del simulador (spec 011) -- envuelve la API de control HTTP existente
// (ControlServer.java) como herramientas MCP. No reemplaza la API: cada herramienta de aquí es
// una llamada HTTP directa a un endpoint que ya existe y sigue funcionando igual por curl/
// httpclient/*.http. Sin autenticación propia -- hereda el mismo nivel de acceso que la API de
// control hoy (pensada para uso interno de desarrollo/QA, ver AGENTS.md &sect;6 del repo Java).
//
// Configuración por variables de entorno:
//   SIMULATOR_URL   URL de la API de control. Default http://localhost:8089.
//   MCP_TRANSPORT   "http" (despliegue normal: servicio junto al simulador, ver docker-compose.yml)
//                   o "stdio" (default: desarrollo local, Claude Code lo lanza como subproceso).
//   MCP_PORT        Puerto del modo HTTP. Default 8090.

import { createServer } from "node:http";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { z } from "zod";

const BASE_URL = process.env.SIMULATOR_URL ?? "http://localhost:8089";
const TRANSPORT = process.env.MCP_TRANSPORT ?? "stdio";
const PORT = Number(process.env.MCP_PORT ?? 8090);

async function callApi(method, path, body) {
	const res = await fetch(new URL(path, BASE_URL), {
		method,
		headers: body ? { "Content-Type": "application/json" } : undefined,
		body: body ? JSON.stringify(body) : undefined,
	});
	const text = await res.text();
	let parsed;
	try {
		parsed = text ? JSON.parse(text) : {};
	} catch {
		parsed = { raw: text };
	}
	return { status: res.status, body: parsed };
}

function toolResult(result) {
	return {
		content: [{ type: "text", text: JSON.stringify(result, null, 2) }],
		isError: result.status >= 400,
	};
}

// Cada llamada crea su propio McpServer: en modo HTTP sin estado (ver abajo) el SDK exige un
// servidor + transporte por petición; en stdio se crea uno solo al arrancar.
function buildServer() {
	const server = new McpServer({
		name: "banxico-simulator",
		version: "0.1.0",
	});

	server.tool(
		"simulator_health",
		"Liveness del simulador (GET /health). Úsalo primero para confirmar que el simulador está " +
			"arriba antes de disparar cualquier otra herramienta.",
		{},
		async () => toolResult(await callApi("GET", "/health")),
	);

	server.tool(
		"simulator_session",
		"Estado de la sesión SPEI más reciente (GET /session) -- si minos está conectado, en qué fase " +
			"del handshake, y si sigue viva.",
		{},
		async () => toolResult(await callApi("GET", "/session")),
	);

	server.tool(
		"simulator_send_abono_valido",
		"Dispara un abono de prueba con contenido válido, tipo de pago 01 fijo (POST /abonos/validos). " +
			"Requiere una sesión SPEI viva -- revisa primero con simulator_session.",
		{},
		async () => toolResult(await callApi("POST", "/abonos/validos")),
	);

	server.tool(
		"simulator_send_abono_invalido",
		"Dispara un abono de prueba con contenido deliberadamente inválido (RFC roto), tipo de pago 01 " +
			"fijo (POST /abonos/invalidos). Requiere una sesión SPEI viva.",
		{},
		async () => toolResult(await callApi("POST", "/abonos/invalidos")),
	);

	server.tool(
		"simulator_send_abono",
		"Dispara un abono de cualquier tipo de pago del catálogo real SPEI (0-36, sin 13/14), con " +
			"clave de rastreo y modo de firma configurables (specs 002 devoluciones, 004 firmas, 006 " +
			"folio duplicado). 'campos' debe traer los nombres de campo que ese tipo de pago requiere " +
			"-- ver PaymentType.java en el repo para el catálogo exacto por tipo (los campos opcionales " +
			"pueden omitirse). Requiere una sesión SPEI viva.",
		{
			tipoPg: z.number().int().describe("Código de tipo de pago SPEI (0-36, sin 13 ni 14)."),
			trackingKey: z.string().optional().describe("Clave de rastreo. Si se omite, se autogenera."),
			firma: z.enum(["valida", "vacia", "corrupta"]).default("valida")
				.describe("valida = firma real; vacia = 32 ceros (sin firma); corrupta = firma real con un byte alterado."),
			campos: z.record(z.string(), z.string())
				.describe("Mapa de nombre de campo -> valor, según el catálogo de PaymentType para tipoPg."),
		},
		async ({ tipoPg, trackingKey, firma, campos }) =>
			toolResult(await callApi("POST", "/abonos", { tipoPg, trackingKey, firma, campos })),
	);

	server.tool(
		"simulator_stop_heartbeat",
		"Suspende deliberadamente el AreYouAlive saliente de la sesión activa (POST /heartbeat/detener, " +
			"spec 009) -- para medir cuánto tarda minos en cerrar la sesión por su timeout de lectura de " +
			"6 segundos. Requiere una sesión SPEI viva.",
		{},
		async () => toolResult(await callApi("POST", "/heartbeat/detener")),
	);

	server.tool(
		"simulator_list_test_runs",
		"Lista las corridas de prueba persistidas (GET /test-runs), más reciente primero -- cada una " +
			"con su id, canal (SPEI/ARA) y cuándo empezó.",
		{},
		async () => toolResult(await callApi("GET", "/test-runs")),
	);

	server.tool(
		"simulator_test_run_events",
		"Lista, en orden cronológico, cada mensaje enviado/recibido de una corrida (GET " +
			"/test-runs/{id}/events) -- incluye el hex crudo del wire, útil para diagnosticar " +
			"desalineamientos o fallas de firma comparando contra lo esperado.",
		{ runId: z.number().int().describe("Id de la corrida, de simulator_list_test_runs.") },
		async ({ runId }) => toolResult(await callApi("GET", `/test-runs/${runId}/events`)),
	);

	server.tool(
		"simulator_start_load_campaign",
		"Spec 012 -- inicia una campaña de volumen que el simulador genera internamente (NO dispares " +
			"esto en un bucle desde el agente -- una sola llamada arranca la campaña completa). Modo " +
			"'sostenida': tasa fija por una duración. Modo 'rampa': sube la tasa hasta la primera falla " +
			"o hasta un tope de seguridad -- útil para encontrar el techo real de esta infraestructura. " +
			"Corre sobre la única sesión SPEI activa. Requiere una sesión SPEI viva.",
		{
			modo: z.enum(["sostenida", "rampa"]),
			tipoPg: z.number().int().describe("Código de tipo de pago SPEI (0-36, sin 13 ni 14)."),
			campos: z.record(z.string(), z.string())
				.describe("Mapa de nombre de campo -> valor, según el catálogo de PaymentType para tipoPg. Se reutiliza para cada envío de la campaña."),
			tasaPorMinuto: z.number().int().optional().describe("Modo sostenida: tasa objetivo fija."),
			duracionSegundos: z.number().int().optional().describe("Modo sostenida: duración; se omite para indefinida (detener manualmente)."),
			tasaInicialPorMinuto: z.number().int().optional().describe("Modo rampa: tasa de arranque."),
			incrementoPorMinuto: z.number().int().optional().describe("Modo rampa: cuánto sube la tasa por escalón."),
			segundosPorEscalon: z.number().int().optional().describe("Modo rampa: cada cuántos segundos sube un escalón."),
			tasaMaxima: z.number().int().optional().describe("Modo rampa: tope de seguridad -- se detiene sola si lo alcanza sin fallar antes."),
		},
		async (args) => toolResult(await callApi("POST", "/abonos/carga", args)),
	);

	server.tool(
		"simulator_load_campaign_status",
		"Estado y métricas de una campaña de volumen (spec 012): enviados, fallidos, tasa actual, " +
			"si sigue corriendo o ya terminó y por qué.",
		{ id: z.string().describe("Id de campaña, devuelto por simulator_start_load_campaign.") },
		async ({ id }) => toolResult(await callApi("GET", `/abonos/carga/${id}`)),
	);

	server.tool(
		"simulator_stop_load_campaign",
		"Detiene manualmente una campaña de volumen en curso (spec 012).",
		{ id: z.string().describe("Id de campaña a detener.") },
		async ({ id }) => toolResult(await callApi("POST", `/abonos/carga/${id}/detener`)),
	);

	return server;
}

if (TRANSPORT === "http") {
	startHttp();
} else {
	await buildServer().connect(new StdioServerTransport());
}

// Modo HTTP (spec 011 §"Despliegue junto a la API"): corre en el mismo host que el simulador, así
// quien prueba solo necesita la URL -- ni clonar el repo ni tener Node instalado. Streamable HTTP
// sin estado: cada POST /mcp es independiente (no hay sesiones MCP que expirar ni que compartir
// entre réplicas), que es lo que pide una API de control sin estado propio del lado del MCP.
function startHttp() {
	const httpServer = createServer(async (req, res) => {
		const { pathname } = new URL(req.url, "http://localhost");
		if (pathname === "/health") {
			return sendJson(res, 200, { status: "ok", simulatorUrl: BASE_URL });
		}
		if (pathname !== "/mcp") {
			return sendJson(res, 404, { error: "no-encontrado", detalle: "El endpoint MCP es /mcp" });
		}
		if (req.method !== "POST") {
			// Sin estado: no hay stream GET de notificaciones ni DELETE de sesión.
			return sendJson(res, 405, { jsonrpc: "2.0", error: { code: -32000, message: "Método no permitido" }, id: null });
		}
		try {
			const body = await readJson(req);
			const server = buildServer();
			const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
			res.on("close", () => {
				transport.close();
				server.close();
			});
			await server.connect(transport);
			await transport.handleRequest(req, res, body);
		} catch (e) {
			console.error("[banxico-simulator-mcp] error atendiendo /mcp:", e);
			if (!res.headersSent) {
				sendJson(res, 500, { jsonrpc: "2.0", error: { code: -32603, message: "Error interno" }, id: null });
			}
		}
	});
	httpServer.listen(PORT, () => {
		console.error(`[banxico-simulator-mcp] MCP por HTTP en :${PORT}/mcp -> API ${BASE_URL}`);
	});
}

function readJson(req) {
	return new Promise((resolve, reject) => {
		let data = "";
		req.on("data", (chunk) => (data += chunk));
		req.on("end", () => {
			try {
				resolve(data ? JSON.parse(data) : undefined);
			} catch (e) {
				reject(e);
			}
		});
		req.on("error", reject);
	});
}

function sendJson(res, status, body) {
	res.writeHead(status, { "Content-Type": "application/json" }).end(JSON.stringify(body));
}
