###############################################################################
# Full Environment — Variables
# Finding A Bed Tonight ($100+/month target)
###############################################################################

variable "aws_region" {
  description = "AWS region for the deployment"
  type        = string
  default     = "us-east-1"
}

variable "db_master_username" {
  description = "Master username for the RDS instance"
  type        = string
  sensitive   = true
}

variable "db_master_password" {
  description = "Master password for the RDS instance"
  type        = string
  sensitive   = true
}

variable "db_app_password" {
  description = "Password for the fabt_app non-superuser role"
  type        = string
  sensitive   = true
}

variable "jwt_secret" {
  description = "JWT signing secret"
  type        = string
  sensitive   = true
}

variable "backend_image" {
  description = "Docker image for the backend container"
  type        = string
  default     = "ghcr.io/ccradle/finding-a-bed-tonight-backend:latest"
}

variable "frontend_image" {
  description = "Docker image for the frontend container"
  type        = string
  default     = "ghcr.io/ccradle/finding-a-bed-tonight-frontend:latest"
}

variable "cors_origins" {
  description = "Allowed CORS origins"
  type        = string
  default     = "*"
}
