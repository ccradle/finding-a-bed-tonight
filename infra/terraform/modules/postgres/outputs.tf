###############################################################################
# PostgreSQL Module — Outputs
# Finding A Bed Tonight
###############################################################################

output "endpoint" {
  description = "RDS instance endpoint (host:port)"
  value       = aws_db_instance.postgres.endpoint
}

output "address" {
  description = "RDS instance hostname"
  value       = aws_db_instance.postgres.address
}

output "port" {
  description = "RDS instance port"
  value       = aws_db_instance.postgres.port
}

output "database_name" {
  description = "Name of the database"
  value       = aws_db_instance.postgres.db_name
}

output "app_role_username" {
  description = "Username of the non-superuser app role"
  value       = "fabt_app"
}

output "app_role_password" {
  description = "Password of the non-superuser app role"
  value       = var.db_app_password
  sensitive   = true
}

output "security_group_id" {
  description = "Security group ID of the RDS instance"
  value       = aws_security_group.postgres.id
}

output "jdbc_url" {
  description = "JDBC connection URL for the app role"
  value       = "jdbc:postgresql://${aws_db_instance.postgres.address}:${aws_db_instance.postgres.port}/${aws_db_instance.postgres.db_name}"
}
