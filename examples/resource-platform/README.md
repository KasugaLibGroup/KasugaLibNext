# Resource platform example

See [the API and lifecycle contract](../../doc/reference/resource-platform.md) for host registration.
The host approves this directory as source `example`, root `main`, explicitly decodes
`kasuga.resources.json`, and registers `ManagedScriptEntryPoint` with an engine lookup and
its own trust/permission policy. Files do not mount or execute themselves.

`script-reference.json` illustrates a value to embed in an opt-in data-driven handler;
it does not register an MC block or a new data-driven factory. Decode it without running
scripts, bind after package readiness, then submit a ticket with argument `41` to obtain `42`.

Untrusted Javet packages remain disabled until its safety capabilities are verified.
