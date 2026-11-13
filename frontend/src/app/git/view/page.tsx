"use client";

import Link from "next/link";
import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useSession } from "@/components/app";
import { JobBadge } from "@/components/badges";
import { ManagedBadge, ManagedNote } from "@/components/managed";
import { GitForm, gitBody, gitFormFrom } from "@/components/GitForm";
import { Button, Copyable, Empty, Field, KeyValues, LogView, Menu, Modal, Notice, PageHeader, Panel, Spinner, Tabs, inputClass, useConfirm, useToast } from "@/components/ui";
import { api } from "@/lib/api";
import { ago, dateTime, duration, shortId } from "@/lib/format";
import { usePoll } from "@/lib/hooks";
import { useManaged } from "@/lib/managed";
import type { DeploymentJob, GitRepo } from "@/lib/types";

type Tab = "deployments" | "settings";

export default function RepoPage() {
  return (
    <Suspense>
      <Repo />
    </Suspense>
  );
}

function Repo() {
  const id = useSearchParams().get("id");
  const { session } = useSession();
  const router = useRouter();
  const toast = useToast();
  const confirm = useConfirm();
  const [tab, setTab] = useState<Tab>("deployments");
  const { data: repo, error, refresh } = usePoll<GitRepo>(id ? `/api/git/repositories/${id}` : null, 10000);
  const { data: allJobs, error: jobsError, refresh: refreshJobs } = usePoll<DeploymentJob[]>("/api/git/jobs", 3000);
  const jobs = (allJobs ?? []).filter((j) => j.gitRepositoryId === id);
  const [selected, setSelected] = useState<string | null>(null);
  const [deploying, setDeploying] = useState(false);
  const [commitOpen, setCommitOpen] = useState(false);
  const [commit, setCommit] = useState("");
  const [saveBusy, setSaveBusy] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const managed = useManaged().find("gitRepositories", id);

  const shown = selected ?? jobs[0]?.id ?? null;

  if (!id) return null;
  if (error && !repo) return <Notice tone="error">{error}</Notice>;
  if (!repo) return <Spinner />;

  const deploy = async (sha?: string) => {
    setDeploying(true);
    try {
      const j = await api.post<DeploymentJob>(`/api/git/repositories/${id}/deploy`, sha ? { commitSha: sha } : {});
      setSelected(j.id);
      setTab("deployments");
      refreshJobs();
    } catch (e) {
      toast((e as Error).message, "error");
    } finally {
      setDeploying(false);
    }
  };

  const running = jobs.find((j) => j.status === "RUNNING" || j.status === "PENDING");

  return (
    <>
      <PageHeader
        title={repo.name}
        back={{ href: "/git/", label: "Git" }}
        intro={
          <>
            {repo.repositoryUrl} · {repo.branch} · {repo.deploymentType === "DOCKERFILE" ? "Dockerfile" : "Compose file"}
            {repo.lastDeployedAt ? `, deployed ${ago(repo.lastDeployedAt)}` : ""}
          </>
        }
      >
        {managed && <ManagedBadge entry={managed.key} />}
        <Button busy={deploying} disabled={!!running} onClick={() => deploy()}>
          {running ? "Deploying" : "Deploy now"}
        </Button>
        <Menu
          items={[
            { label: "Deploy a specific commit", onClick: () => setCommitOpen(true) },
            {
              label: "Check for new commits",
              onClick: async () => {
                try {
                  const r = await api.post<{ triggered: boolean }>(`/api/git/repositories/${id}/poll`);
                  toast(r.triggered ? "New commit found, deploying" : "No new commits");
                  refreshJobs();
                } catch (e) {
                  toast((e as Error).message, "error");
                }
              },
            },
            session?.admin &&
              !managed && {
              label: "Delete repository",
              danger: true,
              onClick: async () => {
                if (!(await confirm({ title: `Delete ${repo.name}?`, body: "Running containers and stacks stay as they are; only the repository and its deploy history are removed from Docker Manager.", confirm: "Delete", danger: true }))) return;
                await api.del(`/api/git/repositories/${id}`);
                router.push("/git/");
              },
            },
          ]}
        />
      </PageHeader>

      {repo.driftStatus === "BEHIND" && (
        <div className="mb-6">
          <Notice tone="warn">
            The branch has newer commits than what is deployed ({shortId(repo.lastCommitSha ?? "").slice(0, 7)} deployed, {shortId(repo.latestRemoteCommitSha ?? "").slice(0, 7)}{" "}
            on {repo.branch}).
          </Notice>
        </div>
      )}

      <Tabs<Tab>
        value={tab}
        onChange={setTab}
        tabs={[
          { value: "deployments", label: "Deployments", count: jobs.length },
          ...(session?.admin ? [{ value: "settings" as Tab, label: "Settings" }] : []),
        ]}
      />

      {tab === "deployments" && (
        <div className="grid gap-6 lg:grid-cols-[minmax(0,20rem)_1fr]">
          <div className="flex flex-col gap-6 min-w-0">
            <Panel title="Webhook">
              {repo.webhookEnabled && !repo.webhookUrl ? (
                <p className="text-[14px] text-slate">On. An administrator can see and copy the webhook URL.</p>
              ) : repo.webhookEnabled && repo.webhookUrl ? (
                <>
                  <p className="text-[13px] text-slate mb-2">Add this URL to the repository&apos;s webhooks (content type JSON, push events). Keep it private.</p>
                  <div className="rounded-lg bg-well px-3 py-2">
                    <Copyable wrap text={repo.webhookUrl} display={repo.webhookUrl.replace(/[^/]{12}$/, "…")} className="w-full justify-between" />
                  </div>
                  <p className="text-[12.5px] text-slate mt-2">Also set the webhook secret to the last part of the URL (GitHub, Gitea). Signed requests are verified and deploy the exact pushed commit; unsigned ones deploy the branch&apos;s latest commit.</p>
                </>
              ) : (
                <p className="text-[14px] text-slate">Off. Turn it on in Settings to deploy on every push.</p>
              )}
            </Panel>
            <Panel title="History" pad={false}>
              {jobsError && !allJobs ? (
                <p className="px-5 pb-5 text-[14px] text-slate">You don&apos;t have permission to see deployments.</p>
              ) : jobs.length === 0 ? (
                <p className="px-5 pb-5 text-[14px] text-slate">No deployments yet.</p>
              ) : (
                <ul className="divide-y divide-line border-t border-line">
                  {jobs.slice(0, 30).map((j) => (
                    <li key={j.id}>
                      <button onClick={() => setSelected(j.id)} className={`w-full text-left px-5 py-3 hover:bg-well/60 ${shown === j.id ? "bg-well" : ""}`}>
                        <span className="flex items-center justify-between gap-2">
                          <JobBadge status={j.status} />
                          <span className="text-[12px] text-slate">{ago(j.createdAt)}</span>
                        </span>
                        <span className="block text-[12.5px] text-slate mt-1">
                          {j.triggerType === "WEBHOOK" ? "Push" : j.triggerType === "POLLING" ? "New commit found" : "Manual"}
                          {j.commitSha && ` · ${j.commitSha.slice(0, 7)}`}
                        </span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </Panel>
          </div>
          {shown ? (
            <JobLog jobId={shown} onCancel={refreshJobs} />
          ) : jobsError && !allJobs ? (
            <Empty title="Deployment history is hidden">Ask an administrator for permission to view deployments.</Empty>
          ) : (
            <Empty title="Nothing deployed yet">Press Deploy now to check out {repo.branch} and deploy it.</Empty>
          )}
        </div>
      )}

      {tab === "settings" && managed && <ManagedNote what="This repository" />}

      {tab === "settings" && !managed && (
        <GitForm
          key={repo.id + repo.lastDeployedAt}
          initial={gitFormFrom(repo, repo.dockerHostId)}
          existing={repo}
          busy={saveBusy}
          error={saveError}
          submitLabel="Save changes"
          onSubmit={async (v) => {
            setSaveBusy(true);
            setSaveError(null);
            try {
              await api.put(`/api/git/repositories/${id}`, gitBody(v));
              toast("Saved");
              refresh();
            } catch (e) {
              setSaveError((e as Error).message);
            } finally {
              setSaveBusy(false);
            }
          }}
        />
      )}

      <Modal
        open={commitOpen}
        onClose={() => setCommitOpen(false)}
        title="Deploy a specific commit"
        footer={
          <>
            <Button tone="quiet" onClick={() => setCommitOpen(false)}>
              Cancel
            </Button>
            <Button
              disabled={!/^[0-9a-fA-F]{7,64}$/.test(commit.trim())}
              onClick={() => {
                setCommitOpen(false);
                deploy(commit.trim());
              }}
            >
              Deploy
            </Button>
          </>
        }
      >
        <Field label="Commit SHA" hint={`A commit on ${repo.branch}, e.g. to go back to an earlier version.`}>
          <input className={`${inputClass} font-mono`} value={commit} onChange={(e) => setCommit(e.target.value)} placeholder="a1b2c3d" autoFocus />
        </Field>
      </Modal>
    </>
  );
}

function JobLog({ jobId, onCancel }: { jobId: string; onCancel: () => void }) {
  const [poll, setPoll] = useState(2000);
  const { data: job } = usePoll<DeploymentJob>(`/api/git/jobs/${jobId}`, poll);
  const toast = useToast();
  const active = job && (job.status === "RUNNING" || job.status === "PENDING");
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setPoll(active || !job ? 1500 : 0);
  }, [active, job]);
  if (!job) return <Spinner />;
  const took = job.completedAt && job.startedAt ? duration(job.completedAt - job.startedAt) : "";
  return (
    <Panel
      title={
        <span className="flex items-center gap-2">
          <JobBadge status={job.status} />
          <span className="text-[14px] font-normal text-slate">{dateTime(job.createdAt)}</span>
        </span>
      }
      action={
        active ? (
          <Button
            tone="quiet"
            size="sm"
            onClick={async () => {
              await api.post(`/api/git/jobs/${jobId}/cancel`);
              toast("Cancelling");
              onCancel();
            }}
          >
            Cancel
          </Button>
        ) : (
          took && <span className="text-[13px] text-slate">took {took}</span>
        )
      }
    >
      {job.errorMessage && (
        <div className="mb-3">
          <Notice tone="error">{job.errorMessage}</Notice>
        </div>
      )}
      <KeyValues rows={[["Commit", job.commitSha ? <Copyable key="c" text={job.commitSha} display={job.commitSha.slice(0, 12)} /> : "Newest on the branch"]]} />
      <div className="mt-4">
        <LogView text={job.logs ?? ""} height="h-[480px]" empty={active ? "Starting…" : "No output."} />
      </div>
      {job.status === "SUCCESS" && (
        <p className="text-[13px] text-slate mt-3">
          See what&apos;s running on{" "}
          <Link href="/containers/" className="underline underline-offset-2">
            Containers
          </Link>{" "}
          or{" "}
          <Link href="/stacks/" className="underline underline-offset-2">
            Stacks
          </Link>
          .
        </p>
      )}
    </Panel>
  );
}
