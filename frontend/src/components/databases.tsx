"use client";

import { Field, Segmented, inputClass } from "@/components/ui";

export type Reach = "containers" | "local" | "network";

/** A port PostgreSQL can be published on (unprivileged ports only). */
export function validPort(port: string) {
  const n = Number(port);
  return /^\d+$/.test(port) && n >= 1024 && n <= 65535;
}

/** Where a PostgreSQL server can be reached from: its own network only, or also a port on the host. */
export function ReachPicker({ reach, setReach, port, setPort }: { reach: Reach; setReach: (v: Reach) => void; port: string; setPort: (v: string) => void }) {
  return (
    <>
      <div>
        <div className="text-[13px] font-medium mb-1.5">Who can connect</div>
        <Segmented
          label="Who can connect"
          value={reach}
          onChange={setReach}
          options={[
            { value: "containers", label: "Containers only" },
            { value: "local", label: "This machine" },
            { value: "network", label: "The network" },
          ]}
        />
        <p className="text-[13px] text-slate mt-1.5">
          {reach === "containers" && "Recommended. No port is opened; containers you connect to the server reach it on its own network."}
          {reach === "local" && "Also opens a port on 127.0.0.1, for tools on the server itself (or an SSH tunnel)."}
          {reach === "network" && "Opens a port on every network interface. Make sure a firewall limits who can reach it."}
        </p>
      </div>
      {reach !== "containers" && (
        <Field label="Port" error={port && !validPort(port) ? "Choose a port between 1024 and 65535" : null}>
          <input className={inputClass} inputMode="numeric" value={port} onChange={(e) => setPort(e.target.value.replace(/\D/g, ""))} />
        </Field>
      )}
    </>
  );
}
