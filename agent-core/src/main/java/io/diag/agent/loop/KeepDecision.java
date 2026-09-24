package io.diag.agent.loop;

public record KeepDecision(boolean keep,String keepType,String reason) {
}
