###############################################################################
# Kafka Module — Variables
# Finding A Bed Tonight
###############################################################################

variable "tier" {
  description = "Deployment tier (must be full for Kafka)"
  type        = string
  default     = "full"
  validation {
    condition     = var.tier == "full"
    error_message = "Kafka module is only available in the full tier"
  }
}

variable "vpc_id" {
  description = "VPC ID where the MSK cluster will be created"
  type        = string
}

variable "private_subnet_ids" {
  description = "List of private subnet IDs for the MSK cluster"
  type        = list(string)
}

variable "app_security_group_id" {
  description = "Security group ID of the application (ECS tasks)"
  type        = string
}

variable "tags" {
  description = "Additional tags to apply to all resources"
  type        = map(string)
  default     = {}
}
