###############################################################################
# PostgreSQL Module — RDS Instance, Security Group, Parameter Group
# Finding A Bed Tonight
###############################################################################

locals {
  common_tags = merge(var.tags, {
    project = "finding-a-bed-tonight"
    tier    = var.tier
    module  = "postgres"
  })

  instance_class = var.tier == "lite" ? "db.t3.micro" : "db.t3.small"
  multi_az       = var.tier == "lite" ? false : true
}

###############################################################################
# Security Group
###############################################################################

resource "aws_security_group" "postgres" {
  name_prefix = "fabt-${var.tier}-postgres-"
  description = "Allow PostgreSQL access from application security group"
  vpc_id      = var.vpc_id

  ingress {
    description     = "PostgreSQL from app"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [var.app_security_group_id]
  }

  egress {
    description = "Allow all outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-postgres-sg"
  })

  lifecycle {
    create_before_destroy = true
  }
}

###############################################################################
# DB Subnet Group
###############################################################################

resource "aws_db_subnet_group" "postgres" {
  name       = "fabt-${var.tier}-postgres"
  subnet_ids = var.private_subnet_ids

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-postgres-subnet-group"
  })
}

###############################################################################
# Parameter Group (RLS-related settings)
###############################################################################

resource "aws_db_parameter_group" "postgres" {
  name_prefix = "fabt-${var.tier}-pg16-"
  family      = "postgres16"
  description = "FABT PostgreSQL 16 parameter group with RLS settings"

  # Enable row-level security logging for debugging
  parameter {
    name  = "log_statement"
    value = "ddl"
  }

  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }

  # Ensure shared_preload_libraries includes pgaudit for RLS auditing
  parameter {
    name         = "shared_preload_libraries"
    value        = "pgaudit"
    apply_method = "pending-reboot"
  }

  parameter {
    name  = "pgaudit.log"
    value = "ddl,role"
  }

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-pg16-params"
  })

  lifecycle {
    create_before_destroy = true
  }
}

###############################################################################
# RDS Instance
###############################################################################

resource "aws_db_instance" "postgres" {
  identifier = "fabt-${var.tier}-postgres"

  engine         = "postgres"
  engine_version = "16"
  instance_class = local.instance_class
  multi_az       = local.multi_az

  db_name  = var.database_name
  username = var.db_master_username
  password = var.db_master_password

  allocated_storage     = 20
  max_allocated_storage = 100
  storage_type          = "gp3"
  storage_encrypted     = true

  db_subnet_group_name   = aws_db_subnet_group.postgres.name
  vpc_security_group_ids = [aws_security_group.postgres.id]
  parameter_group_name   = aws_db_parameter_group.postgres.name

  publicly_accessible  = false
  skip_final_snapshot  = var.tier == "lite" ? true : false
  final_snapshot_identifier = var.tier == "lite" ? null : "fabt-${var.tier}-postgres-final"

  backup_retention_period = var.tier == "lite" ? 7 : 14
  backup_window           = "03:00-04:00"
  maintenance_window      = "sun:04:00-sun:05:00"

  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]

  deletion_protection = var.tier == "lite" ? false : true

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-postgres"
  })
}

###############################################################################
# Provision non-superuser app role via local-exec
# Creates fabt_app role for RLS enforcement — app never connects as superuser
###############################################################################

resource "null_resource" "create_app_role" {
  depends_on = [aws_db_instance.postgres]

  triggers = {
    db_instance_id = aws_db_instance.postgres.id
  }

  provisioner "local-exec" {
    environment = {
      PGHOST     = aws_db_instance.postgres.address
      PGPORT     = tostring(aws_db_instance.postgres.port)
      PGDATABASE = var.database_name
      PGUSER     = var.db_master_username
      PGPASSWORD = var.db_master_password
    }

    command = <<-EOT
      psql -c "DO \$\$
      BEGIN
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'fabt_app') THEN
          CREATE ROLE fabt_app WITH LOGIN PASSWORD '${var.db_app_password}' NOSUPERUSER;
        ELSE
          ALTER ROLE fabt_app WITH PASSWORD '${var.db_app_password}';
        END IF;
      END
      \$\$;"
      psql -c "GRANT ALL ON ALL TABLES IN SCHEMA public TO fabt_app;"
      psql -c "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO fabt_app;"
    EOT
  }
}
