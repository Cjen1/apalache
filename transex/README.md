# TransEx: The Apalache Transition Explorer

- [Preliminary Design Notes](../docs/src/adr/010rfc-transition-explorer.md)

This project implements a server allowing clients to interactively drive the
Apalache [`TransitionExecutor`](../tla/bmcmt/trex/TransitionExecutor.scala) via
remote procedure calls (RPCs).

The server is implemented using the
[ZIO-gRPC](https://scalapb.github.io/zio-grpc/) stack. And the RPC messages are
defined using protobuf.

## Protobuf specs

The [protobuf](https://developers.google.com/protocol-buffers/docs/proto3)
specifications live in [src/main/protobuf/](src/main/protobuf/).

When this project is compiled, via `sbt transex/compile`, Scala source code is
generated from the protobuf specs into
[./target/scala-2.13/src_managed/main/scalapb/](./target/scala-2.13/src_managed/main/scalapb/).
