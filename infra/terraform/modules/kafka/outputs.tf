###############################################################################
# Kafka Module — Outputs
# Finding A Bed Tonight
###############################################################################

output "bootstrap_brokers" {
  description = "Plaintext bootstrap broker connection string"
  value       = aws_msk_cluster.kafka.bootstrap_brokers
}

output "zookeeper_connect_string" {
  description = "Zookeeper connection string"
  value       = aws_msk_cluster.kafka.zookeeper_connect_string
}

output "cluster_arn" {
  description = "ARN of the MSK cluster"
  value       = aws_msk_cluster.kafka.arn
}

output "security_group_id" {
  description = "Security group ID of the MSK cluster"
  value       = aws_security_group.kafka.id
}
