export type StartRunResponse = {
  runId: string;
  status: "STARTED";
}

export type CancelRunResponse = {
  runId: string;
  cancelled: boolean
}