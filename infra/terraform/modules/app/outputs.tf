###############################################################################
# Application Module — Outputs
# Finding A Bed Tonight
###############################################################################

output "alb_dns_name" {
  description = "DNS name of the Application Load Balancer"
  value       = aws_lb.main.dns_name
}

output "alb_zone_id" {
  description = "Zone ID of the ALB (for Route53 alias records)"
  value       = aws_lb.main.zone_id
}

output "ecs_cluster_name" {
  description = "Name of the ECS cluster"
  value       = aws_ecs_cluster.main.name
}

output "ecs_service_name" {
  description = "Name of the ECS service"
  value       = aws_ecs_service.app.name
}

output "app_security_group_id" {
  description = "Security group ID of the ECS tasks"
  value       = aws_security_group.app.id
}

output "alb_security_group_id" {
  description = "Security group ID of the ALB"
  value       = aws_security_group.alb.id
}

output "app_url" {
  description = "URL to access the application"
  value       = "http://${aws_lb.main.dns_name}"
}
