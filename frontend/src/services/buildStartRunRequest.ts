//src/services/buildStartRunRequest.ts
type Variant = "simulacion" | "operacion" | "colapso";

type StartRunRequest = {
    scenario : "OPERACION" | "SIM_SEMANAL" | "COLAPSO";
    startUtc?: string;
    endUtc?: string;
    horizonHours?: number;
    windowHours?: number;
    seed?: number;
    params?: unknown;
    //ordersSource?: { type: "FILE" | "LIVE"; fileId?: string };
}

const SCENARIO_MAP: Record<Variant, StartRunRequest["scenario"]> = {
    simulacion: "SIM_SEMANAL",
    operacion: "OPERACION",
    colapso: "COLAPSO",
}

//Acá estamos fijando la duración de las ventanas y eso, por escenario
const PRESETS = {
    simulacion: { windowHours: 4, uses: "range" as const },     // pide inicio+fin
    colapso:    { windowHours: 6, uses: "horizon" as const, horizonHours: 24 }, // pide inicio
    operacion:  { uses: "none" as const }, 
}

//Con esto construimos la request por escenario
export function buildStartRunRequest(
    variant: Variant,
    ui: {inicio?: Date; fin?: Date; ordersSource?: { type: "FILE" | "LIVE"; fileId?: string }}
): StartRunRequest {
    const base: StartRunRequest = {
        scenario: SCENARIO_MAP[variant],
    };

    const preset = PRESETS[variant];

    //"range" corresponde a simulación semanal
    if (preset.uses === "range"){
        if (!ui.inicio || !ui.fin) throw new Error("Faltan fechas (inicio y fin).");
        return {
            ...base,
            startUtc: ui.inicio.toISOString(),
            endUtc: ui.fin.toISOString(),
            windowHours: preset.windowHours,
            //ordersSource: ui.ordersSource,
        }
    }

    //"horizon" corresponde a colapso
    if (preset.uses === "horizon"){
        if (!ui.inicio) throw new Error("Falta fecha de inicio.");
        return {
            ...base,
            startUtc: ui.inicio.toISOString(),
            horizonHours: preset.horizonHours, // fijo para colapso
            windowHours: preset.windowHours,   // fijo para colapso
            //ordersSource: ui.ordersSource,
        };
    }

    //Si estamos aca es operación diaría
    if (!ui.inicio) throw new Error("Falta fecha de inicio.");
    return {
        ...base,
        startUtc: ui.inicio.toISOString(),
        //ordersSource: ui.ordersSource,
    };


}